package org.elixir_lang.expander

import com.intellij.codeInsight.highlighting.actions.HighlightUsagesAction
import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.find.usages.api.PsiUsage
import com.intellij.find.usages.api.UsageOptions
import com.intellij.find.usages.impl.AllSearchOptions
import com.intellij.find.usages.impl.buildQuery
import com.intellij.find.usages.impl.searchTargets
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.Utils
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.util.use
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.refactoring.ui.NameSuggestionsField
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.ui.EditorTextField
import com.intellij.ui.UiInterceptors
import com.intellij.util.ui.UIUtil
import org.elixir_lang.declaration.disableExpander
import org.elixir_lang.declaration.enableExpander
import org.elixir_lang.expander.ExpanderSourceRows.read
import org.elixir_lang.expander.ExpanderSourceRows.rows
import org.elixir_lang.expander.ExpanderSourceRows.use
import org.elixir_lang.expander.ExpanderThreads.Companion.ASSERTION_KEY
import org.elixir_lang.intellij_elixir.Quoter
import org.elixir_lang.junit.onPooledThread
import org.elixir_lang.model.psi.config.ConfigKeySymbol
import org.elixir_lang.model.psi.config.caret
import java.lang.ref.Reference
import kotlin.time.Duration.Companion.seconds

/**
 * Gestures the platform runs on the EDT, under the key that makes waiting on another thread there an error in tests as
 * it is in the IDE, with "Run the expander" on: they expand nothing there, answer from what is held, and start the
 * warm-up that holds the rest.
 */
class ExpanderOffTheEdtTest : LegSdkFixtureTestCase() {
    private val holder: ProjectModules get() = ProjectModules.getInstance(project)

    private val warmUp: ExpanderWarmUp get() = ExpanderWarmUp.getInstance(project)

    override fun setUp() {
        // Before the platform counts the threads a test leaves, so the quoter's connection is already open.
        Quoter.compile(":ok", 60.seconds)
        super.setUp()
        TemplateManagerImpl.setTemplateTesting(myFixture.testRootDisposable)
    }

    /** Highlight Usages in File at a call, its file's expansion not held, highlights each occurrence of the word. */
    fun testHighlightUsagesInFileHighlightsTheWord() {
        enableExpander(myFixture.testRootDisposable)
        open()

        assertEquals(WORD, underTheAssertionKey { highlighted() })
    }

    /** Neither gesture expands anything on the EDT, and the file's warm-up is queued. */
    fun testNothingExpandsOnTheEdt() {
        enableExpander(myFixture.testRootDisposable)
        open()
        val before = warmUp.pending

        val (_, runs) = underTheAssertionKey { holder.runs { renamed(); highlighted() } }

        assertEquals(rows("runs" to "", "warm-up queued" to "true"), rows("runs" to runs.joinToString(), "warm-up queued" to "${warmUp.pending !== before}"))
    }

    /** Once the warm-up has held the file, Rename renames in place and Highlight Usages highlights the function's uses. */
    fun testOnceHeldBothGiveTodaysAnswers() {
        enableExpander(myFixture.testRootDisposable)
        open()
        underTheAssertionKey { highlighted() }
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_ESCAPE)
        warmed()

        assertTodaysAnswers()
    }

    /**
     * A usage search of a key on the EDT, the reader held but neither `mix.exs` nor a config table, evaluates no table and
     * finds the setting in the config folder, not the one in the file it imports from outside it; once the warm-up it
     * queues has run, both.
     */
    fun testAConfigKeyUsageSearchReadsOnlyHeldTables() {
        enableExpander(myFixture.testRootDisposable)
        caller(MIX, "mix.exs")
        caller(CONFIG, "config/config.exs")
        caller(EXTRA, "extra/config.exs")
        caller(READER, "lib/reader.ex")
        myFixture.caret("lib/reader.ex", "get_env(:app, :<caret>k)")
        val reader = myFixture.file
        held(reader)

        val (cold, runs) = underTheAssertionKey { holder.runs { usagesIn("config.exs") } }
        warmed()

        assertEquals(
            rows("cold" to "config/config.exs:3 k", "runs" to "", "warm" to "config/config.exs:3 k\nextra/config.exs:2 k"),
            rows("cold" to cold, "runs" to runs.joinToString(), "warm" to underTheAssertionKey { usagesIn("config.exs") }),
        )
    }

    /**
     * A usage search of a key on the EDT, its reader's file held, misses the reader's `__MODULE__` read of its own key,
     * which only the expander names; a search of that key off the EDT afterwards finds it.
     */
    fun testAModuleKeyReadTheEdtMissesIsFoundOffIt() {
        enableExpander(myFixture.testRootDisposable)
        caller(MIX, "mix.exs")
        val config = caller(MODULE_CONFIG, "config/config.exs")
        caller(MODULE_READER, "lib/reader.ex")
        myFixture.caret("lib/reader.ex", "get_env(:app, :<caret>k)")
        val reader = myFixture.file
        held(reader)
        val module = read { config.text.indexOf("Reader") }

        val cold = underTheAssertionKey { usagesIn("reader.ex") }
        warmed()
        val warm = offTheEdt { usagesIn("reader.ex", config, module) }

        assertEquals("on the EDT: $cold", "lib/reader.ex:3 __MODULE__", warm)
        Reference.reachabilityFence(reader)
    }

    /**
     * A remote call in a `quote`, whose qualifier the expansion records no receiver for, resolved on the EDT, its file
     * held, resolves off the EDT afterwards to what the alias names.
     */
    fun testAQuotedRemoteCallResolvedOnTheEdtResolvesOffIt() {
        enableExpander(myFixture.testRootDisposable)
        val b = caller(QUOTED_TARGET, "lib/b.ex")
        caller(QUOTES_ALIAS, "lib/a.ex")
        myFixture.caret("lib/a.ex", "C.<caret>f()")
        val a = myFixture.file
        held(b)
        held(a)
        val offset = myFixture.caretOffset
        val reference = read { a.findReferenceAt(offset) } as PsiPolyVariantReference
        val before = warmUp.pending

        val cold = underTheAssertionKey { resolved(reference) }
        val queued = warmUp.pending !== before
        warmed()
        val warm = offTheEdt { resolved(reference) }

        assertEquals("on the EDT: \"$cold\", warm-up queued: $queued", "lib/b.ex:2", warm)
        Reference.reachabilityFence(b)
    }

    /**
     * The computing value's answer on the EDT, the use's file held, at a head a `.beam`'s macro injected, whose mirror
     * isn't built, is an UNPLACED gap and builds no mirror there; once the warm-up it queues has built it, the head is
     * placed in it, as off the EDT.
     */
    fun testAHeadACompiledMacroInjectedIsUnplacedOnTheEdtUntilItsMirrorIsBuilt() {
        enableExpander(myFixture.testRootDisposable)
        addPlaced()
        val file = caller(CALLS_PLACED, "lib/a.ex")
        held(file)
        val use = use(file, "do: f()", "f()")
        val before = warmUp.pending

        val cold = underTheAssertionKey { ask(use) }
        val queued = warmUp.pending !== before
        warmed()

        assertEquals(
            rows("cold" to "absence UNPLACED", "warm-up queued" to "true", "warm" to offTheEdt { ask(use) }),
            rows("cold" to cold, "warm-up queued" to "$queued", "warm" to underTheAssertionKey { ask(use) }),
        )
    }

    /** With "Run the expander" off, Rename renames in place and Highlight Usages highlights the function's uses, as on main. */
    fun testWithTheExpanderOffNothingChanges() {
        disableExpander(myFixture.testRootDisposable)
        open()

        assertTodaysAnswers()
    }

    /** Rename renames in place and Highlight Usages highlights the function's declaration and call, as on main. */
    private fun assertTodaysAnswers() {
        assertEquals(
            rows("highlighted" to SYMBOL, "rename" to "in place", "text" to COMMENTED_RENAMED),
            underTheAssertionKey { "${rows("highlighted" to highlighted())}\n\n${renamed()}" },
        )
    }

    /** [COMMENTED] as `lib/a.ex`, open with the caret at its call of `ping`. */
    private fun open() {
        caller(COMMENTED, "lib/a.ex")
        myFixture.caret("lib/a.ex", "do: <caret>ping()")
    }

    /** [block]'s value, run on a pooled thread, as the IDE runs what it runs off the EDT. */
    private fun <T> offTheEdt(block: () -> T): T = onPooledThread(30.seconds.inWholeMilliseconds, block)

    /** [block] on the EDT under the key, as the IDE runs it: there, waiting on another thread is an error. */
    private fun <T> underTheAssertionKey(block: () -> T): T =
        Disposer.newDisposable(myFixture.testRootDisposable, ASSERTION_KEY).use { key ->
            Registry.get(ASSERTION_KEY).setValue(true, key)
            block()
        }

    /**
     * Rename at the caret to [NEW_NAME], as Shift+F6 runs it: the action updated off the EDT, as it declares, then
     * performed here, in place or in the dialog, which is accepted; how it ran and the file's text once its edits are made.
     */
    private fun renamed(): String {
        var dialog: String? = null
        UiInterceptors.registerPossible(
            myFixture.testRootDisposable,
            object : UiInterceptors.UiInterceptor<DialogWrapper>(DialogWrapper::class.java) {
                override fun doIntercept(component: DialogWrapper) {
                    dialog = component.javaClass.simpleName
                    val field = UIUtil.findComponentOfType(component.contentPanel, NameSuggestionsField::class.java)!!
                    (field.focusableComponent as EditorTextField).text = NEW_NAME
                    component.close(DialogWrapper.OK_EXIT_CODE)
                }
            },
        )
        val document = myFixture.editor.document
        val before = document.text
        val action = ActionManager.getInstance().getAction(IdeActions.ACTION_RENAME)
        val context = Utils.createAsyncDataContext(EditorUtil.getEditorDataContext(myFixture.editor))
        val event = AnActionEvent.createEvent(action, context, null, ActionPlaces.KEYBOARD_SHORTCUT, ActionUiKind.NONE, null)
        val enabled = offTheEdt {
            ReadAction.computeBlocking<Boolean, Throwable> {
                ActionUtil.updateAction(action, event)
                event.presentation.isEnabled
            }
        }

        if (enabled) ActionUtil.performAction(action, event)
        val inPlace = TemplateManagerImpl.getTemplateState(myFixture.editor) != null
        if (inPlace) {
            myFixture.type(NEW_NAME)
            myFixture.type('\n')
        }
        val how = if (inPlace) "in place" else dialog?.let { "dialog $it" } ?: "nothing"

        if (inPlace || dialog != null) {
            PlatformTestUtil.waitWithEventsDispatching({ "rename $how never edited the file" }, { document.text != before }, 30)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        }

        return rows("rename" to how, "text" to document.text)
    }

    /** Highlight Usages in File at the caret: the file's text with each highlighted range in brackets. */
    private fun highlighted(): String {
        assertTrue("Highlight Usages in File ran", myFixture.testAction(HighlightUsagesAction()).isEnabled)
        val text = myFixture.editor.document.text
        val ranges = myFixture.editor.markupModel.allHighlighters.map { it.startOffset to it.endOffset }.distinct().sortedBy { it.first }
        val marked = StringBuilder()
        var at = 0
        for ((start, end) in ranges) {
            marked.append(text, at, start).append('[').append(text, start, end).append(']')
            at = end
        }

        return marked.append(text, at, text.length).toString()
    }

    /** Where [reference] resolves, on this thread: each target as its file and line, sorted. */
    private fun resolved(reference: PsiPolyVariantReference): String =
        read { reference.multiResolve(false).mapNotNull { it.element?.let(::place) }.sorted().joinToString("\n") }

    /** What a usage search, on this thread, of the key at [offset] in [file] finds in files named [name]: each as its file, line and text. */
    private fun usagesIn(name: String, file: PsiFile = myFixture.file, offset: Int = myFixture.caretOffset): String {
        val options = AllSearchOptions(UsageOptions.createOptions(GlobalSearchScope.allScope(project)), textSearch = false)

        return read {
            val target = searchTargets(file, offset).single { it is ConfigKeySymbol }
            buildQuery(project, target, options).findAll()
                .asSequence()
                .filterIsInstance<PsiUsage>()
                .filter { it.file.name == name }
                .map { "${place(it.file.findElementAt(it.range.startOffset)!!)} ${it.range.substring(it.file.text)}" }
                .sorted()
                .joinToString("\n")
        }
    }

    private companion object {
        const val COMMENTED = "defmodule A do\n  # ping is A's\n  def ping, do: 1\n  def pong, do: ping()\nend"
        const val NEW_NAME = "h"
        const val COMMENTED_RENAMED = "defmodule A do\n  # ping is A's\n  def h, do: 1\n  def pong, do: h()\nend\n"

        /** Each occurrence of `ping`, the comment's too. */
        const val WORD = "defmodule A do\n  # [ping] is A's\n  def [ping], do: 1\n  def pong, do: [ping]()\nend\n"

        /** The function's declaration and its call. */
        const val SYMBOL = "defmodule A do\n  # ping is A's\n  def [ping], do: 1\n  def pong, do: [ping]()\nend\n"

        const val MIX = "defmodule App.MixProject do\n  use Mix.Project\n\n  def project, do: [app: :app, version: \"0.1.0\"]\nend"
        const val CONFIG = "import Config\nimport_config \"../extra/config.exs\"\nconfig :app, :k, 1"
        const val EXTRA = "import Config\nconfig :app, :k, 2"

        const val READER = "defmodule Reader do\n  def k, do: Application.get_env(:app, :k)\nend"

        /** A setting of `:k`, and one of the module key `Reader`. */
        const val MODULE_CONFIG = "import Config\nconfig :app, :k, 1\nconfig :app, Reader, 2"

        /** `Reader` reads `:k`, and its own key as `__MODULE__`, which only the expander names. */
        const val MODULE_READER =
            "defmodule Reader do\n  def k, do: Application.get_env(:app, :k)\n  def m, do: Application.get_env(:app, __MODULE__)\nend"

        const val QUOTED_TARGET = "defmodule B do\n  def f, do: 1\nend"

        /** `C.f()` in a `quote`, which the expansion records no receiver for, `C` aliasing `B`. */
        const val QUOTES_ALIAS = "defmodule A do\n  alias B, as: C\n\n  defmacro m do\n    quote do\n      C.f()\n    end\n  end\nend"
    }
}
