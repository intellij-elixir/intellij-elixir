package org.elixir_lang.model.psi.function

import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.ide.util.PsiNavigationSupport
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.assertNoNavigationAtCaret
import org.elixir_lang.code_insight.assertShowUsagesChosenAtCaret
import org.elixir_lang.code_insight.gotoDeclarationTargetsAtCaret
import org.elixir_lang.code_insight.psiUsagesAtCaret
import org.elixir_lang.code_insight.renameTargetsAtCaret
import org.elixir_lang.code_insight.renameTargetAtCaret
import org.elixir_lang.psi.call.Call
import java.util.concurrent.Callable

/**
 * The name each declaring form writes is a declaration, as a `def`'s is: Ctrl+Click there shows usages, and Find Usages
 * from it lists its uses. A head that a definer's own `defmacro` writes, as `embed_template(name, contents)` in
 * `Mix.Generator`, declares nothing but the macro, so its parameters stay variables.
 */
@Suppress("UnstableApiUsage")
class DeclaringHeadTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
    }

    fun testDef() {
        myFixture.addFileToProject("use.ex", "defmodule U do\n  def run, do: M.snoc(1, 2)\nend\n")
        myFixture.configureByText("m.ex", "defmodule M do\n  def sn<caret>oc(q, x), do: [x | q]\nend\n")

        assertDeclares("use.ex:2")
    }

    fun testDelegationHead() {
        target()
        myFixture.addFileToProject("use.ex", "defmodule U do\n  def run, do: M.snoc(1, 2)\nend\n")
        myFixture.configureByText("m.ex", "defmodule M do\n  defdelegate sn<caret>oc(q, x), to: T\nend\n")

        assertDeclares("use.ex:2")
    }

    fun testDelegationHeadInANestedModule() {
        target()
        myFixture.addFileToProject("use.ex", "defmodule U do\n  def run, do: Outer.Inner.snoc(1, 2)\nend\n")
        myFixture.configureByText(
            "m.ex",
            "defmodule Outer do\n  defmodule Inner do\n    defdelegate sn<caret>oc(q, x), to: T\n  end\nend\n"
        )

        assertDeclares("use.ex:2")
    }

    fun testEExName() {
        myFixture.addFileToProject(
            "eex.ex",
            "defmodule EEx do\n  defmacro function_from_string(kind, name, source, args \\\\ [], options \\\\ []) do\n" +
                "    {kind, name, source, args, options}\n  end\nend\n"
        )
        myFixture.addFileToProject("use.ex", "defmodule U do\n  def run, do: T.hello(1)\nend\n")
        myFixture.configureByText(
            "t.ex",
            "defmodule T do\n  require EEx\n  EEx.function_from_string(:def, :hel<caret>lo, \"<%= a %>\", [:a])\nend\n"
        )

        assertDeclares("use.ex:2")
    }

    fun testEmbedName() {
        myFixture.addFileToProject(
            "generator.ex",
            "defmodule Mix.Generator do\n  defmacro embed_template(name, contents) do\n    {name, contents}\n  end\nend\n"
        )
        myFixture.configureByText(
            "t.ex",
            "defmodule T do\n  import Mix.Generator\n  embed_template(:lo<caret>g, \"<%= @a %>\")\n\n" +
                "  def run, do: log_template(a: 1)\nend\n"
        )

        myFixture.assertShowUsagesChosenAtCaret()
    }

    fun testEExWrittenHead() {
        myFixture.addFileToProject(
            "use.ex",
            "defmodule U do\n  require EEx\n  def run, do: EEx.function_from_file(:def, :x, \"x.eex\")\nend\n"
        )
        myFixture.configureByText(
            "eex.ex",
            "defmodule EEx do\n  defmacro function_from_fi<caret>le(kind, name, file, args \\\\ [], options \\\\ []) do\n" +
                "    {kind, name, file, args, options}\n  end\nend\n"
        )

        assertDeclares("use.ex:3")
        assertNameIsUsed("eex.ex:3")
    }

    fun testEmbedWrittenHead() {
        myFixture.addFileToProject(
            "use.ex",
            "defmodule U do\n  require Mix.Generator\n  def run, do: Mix.Generator.embed_template(:x, \"y\")\nend\n"
        )
        myFixture.configureByText(
            "generator.ex",
            "defmodule Mix.Generator do\n  defmacro embed_tem<caret>plate(name, contents) do\n" +
                "    {name, contents}\n  end\nend\n"
        )

        assertDeclares("use.ex:3")
        assertNameIsUsed("generator.ex:3")
    }

    fun testGuardedWrittenHead() {
        myFixture.addFileToProject(
            "use.ex",
            "defmodule U do\n  require Mix.Generator\n  def run, do: Mix.Generator.embed_template(:x, \"y\")\nend\n"
        )
        myFixture.configureByText(
            "generator.ex",
            "defmodule Mix.Generator do\n  defmacro embed_tem<caret>plate(name, contents) when is_atom(name) do\n" +
                "    {name, contents}\n  end\nend\n"
        )

        assertDeclares("use.ex:3")
        assertNameIsUsed("generator.ex:2", "generator.ex:3")
    }

    /** Kernel's own `defmacro defdelegate(funs, opts)` is shaped like a delegation of `funs`, yet `funs` is its parameter. */
    fun testDelegationWrittenHead() {
        myFixture.configureByText(
            "kernel.ex",
            "defmodule Kernel do\n  defmacro defdelegate(funs, opts) do\n    {fu<caret>ns, opts}\n  end\nend\n"
        )

        assertEquals(
            myFixture.file.text.indexOf("funs"),
            myFixture.gotoDeclarationTargetsAtCaret()?.singleOrNull()?.destination?.textOffset
        )
    }

    fun testParenthesizedDelegationWrittenHead() {
        myFixture.configureByText(
            "kernel.ex",
            "defmodule Kernel do\n  defmacro(defdelegate(funs, opts), do: {fu<caret>ns, opts})\nend\n"
        )

        assertEquals(
            myFixture.file.text.indexOf("funs"),
            myFixture.gotoDeclarationTargetsAtCaret()?.singleOrNull()?.destination?.textOffset
        )
    }

    fun testParenthesizedGuardedDelegationWrittenHead() {
        myFixture.configureByText(
            "kernel.ex",
            "defmodule Kernel do\n  defmacro(defdelegate(funs, opts) when is_list(opts), do: {fu<caret>ns, opts})\nend\n"
        )

        assertEquals(
            myFixture.file.text.indexOf("funs"),
            myFixture.gotoDeclarationTargetsAtCaret()?.singleOrNull()?.destination?.textOffset
        )
    }

    /** A head named like a definer, as in Kernel's own `defmacro def(call, expr \\ nil)`. */
    fun testDefinerNamedHead() {
        myFixture.addFileToProject("use.ex", "defmodule U do\n  require K\n  def run, do: K.def(1)\nend\n")
        myFixture.configureByText("k.ex", "defmodule K do\n  defmacro de<caret>f(call, expr \\\\ nil), do: {call, expr}\nend\n")

        assertDeclares("use.ex:3")
    }

    /** An EEx name that is not an atom is a call: it declares nothing, and Go To from it lands on its definition. */
    fun testComputedEExNameIsACall() {
        eex()
        myFixture.configureByText(
            "t.ex",
            "defmodule T do\n  require EEx\n  def make_name, do: :x\n  EEx.function_from_string(:def, make_na<caret>me(), \"hi\")\nend\n"
        )

        assertEquals(
            listOf(myFixture.file.text.indexOf("make_name")),
            myFixture.gotoDeclarationTargetsAtCaret()?.map { it.destination?.textOffset }
        )
    }

    /** A call in an embed name's interpolation runs when the embed is defined: Go To from it lands on its definition. */
    fun testCallInAnInterpolatedEmbedNameIsACall() {
        generator()
        myFixture.configureByText(
            "t.ex",
            "defmodule T do\n  import Mix.Generator\n  def make_name, do: \"x\"\n" +
                "  embed_template(:\"#{make_na<caret>me()}\", \"y\")\nend\n"
        )

        assertEquals(
            listOf(myFixture.file.text.indexOf("make_name")),
            myFixture.gotoDeclarationTargetsAtCaret()?.map { it.destination?.textOffset }
        )
    }

    fun testFindUsagesListsACallInAnInterpolatedEmbedName() {
        generator()
        myFixture.configureByText(
            "t.ex",
            "defmodule T do\n  import Mix.Generator\n  def make_na<caret>me, do: \"x\"\n" +
                "  embed_template(:\"#{make_name()}\", \"y\")\nend\n"
        )

        assertEquals(listOf("t.ex:4"), uses())
    }

    fun testCallInAnInterpolatedEExNameIsACall() {
        eex()
        myFixture.configureByText(
            "t.ex",
            "defmodule T do\n  require EEx\n  def make_name, do: \"x\"\n" +
                "  EEx.function_from_string(:def, :\"#{make_na<caret>me()}\", \"hi\")\nend\n"
        )

        assertEquals(
            listOf(myFixture.file.text.indexOf("make_name")),
            myFixture.gotoDeclarationTargetsAtCaret()?.map { it.destination?.textOffset }
        )
    }

    fun testVariableInAnInterpolatedEmbedNameIsRead() {
        generator()
        myFixture.configureByText(
            "t.ex",
            "defmodule T do\n  import Mix.Generator\n" +
                "  for name <- [:a] do\n    embed_template(:\"#{na<caret>me}_x\", \"y\")\n  end\nend\n"
        )

        assertEquals(
            listOf(myFixture.file.text.indexOf("name")),
            myFixture.gotoDeclarationTargetsAtCaret()?.map { it.destination?.textOffset }
        )
    }

    /** A call in a clause head's `unquote` runs when the clause is defined: Go To from it lands on its definition. */
    fun testCallInAnUnquotedClauseNameIsACall() {
        myFixture.configureByText(
            "t.ex",
            "defmodule T do\n  def make_name, do: :x\n\n  defmacro m do\n    quote do\n" +
                "      def unquote(make_na<caret>me())(a), do: a\n    end\n  end\nend\n"
        )

        assertEquals(
            listOf(myFixture.file.text.indexOf("make_name")),
            myFixture.gotoDeclarationTargetsAtCaret()?.map { it.destination?.textOffset }
        )
    }

    /** A call named like an embed that is not `Mix.Generator`'s declares nothing. */
    fun testQualifiedLookalikeEmbedDeclaresNothing() {
        myFixture.addFileToProject("other.ex", "defmodule Other do\n  def embed_text(name, contents), do: {name, contents}\nend\n")
        myFixture.configureByText("m.ex", "defmodule M do\n  def run, do: Other.embed_text(:er<caret>ror, \"x\")\nend\n")

        myFixture.assertNoNavigationAtCaret()
    }

    fun testLocalLookalikeEmbedDeclaresNothing() {
        myFixture.configureByText(
            "m.ex",
            "defmodule M do\n  def embed_text(name, contents), do: {name, contents}\n" +
                "  def run, do: embed_text(:er<caret>ror, \"x\")\nend\n"
        )

        myFixture.assertNoNavigationAtCaret()
    }

    /** An embed's name declares even when it also names a module: Ctrl+Click there shows usages. */
    fun testEmbedNameThatNamesAModuleDeclares() {
        generator()
        myFixture.addFileToProject("target.ex", "defmodule :target do\nend\n")
        myFixture.configureByText(
            "t.ex",
            "defmodule T do\n  import Mix.Generator\n  embed_template(:tar<caret>get, \"y\")\n  def run, do: target_template([])\nend\n"
        )

        myFixture.assertShowUsagesChosenAtCaret()
    }

    /** The atom a lookalike embed takes is an ordinary atom: Go To from one naming a module lands on that module. */
    fun testQualifiedLookalikeEmbedsAtomNamesItsModule() {
        myFixture.addFileToProject("other.ex", "defmodule Other do\n  def embed_text(name, contents), do: {name, contents}\nend\n")
        myFixture.addFileToProject("target.ex", "defmodule :target do\nend\n")
        myFixture.configureByText("m.ex", "defmodule M do\n  def run, do: Other.embed_text(:tar<caret>get, \"x\")\nend\n")

        assertEquals(listOf("target.ex"), myFixture.gotoDeclarationTargetsAtCaret()?.map { it.destination?.containingFile?.name })
    }

    fun testLocalLookalikeEmbedsAtomNamesItsModule() {
        myFixture.addFileToProject("target.ex", "defmodule :target do\nend\n")
        myFixture.configureByText(
            "m.ex",
            "defmodule M do\n  def embed_text(name, contents), do: {name, contents}\n" +
                "  def run, do: embed_text(:tar<caret>get, \"x\")\nend\n"
        )

        assertEquals(listOf("target.ex"), myFixture.gotoDeclarationTargetsAtCaret()?.map { it.destination?.containingFile?.name })
    }

    /** A caret before a quoted atom's opening quote is inside the atom, so on the name. */
    fun testQuotedAtomOfAnUnquotedNameDeclaresAtItsQuote() {
        myFixture.addFileToProject("use.ex", "defmodule U do\n  def run, do: M.\"a b\"(1)\nend\n")
        myFixture.configureByText("m.ex", "defmodule M do\n  def unquote(:<caret>\"a b\")(x), do: x\nend\n")

        assertDeclares("use.ex:2")
    }

    fun testRenameAtACallOfAnUnquotedNameRewritesItsAtom() {
        myFixture.configureByText("m.ex", "defmodule M do\n  def unquote(:t)(), do: 1\n\n  def run, do: <caret>t()\nend\n")
        myFixture.renameTargetAtCaret("u")

        assertEquals("defmodule M do\n  def unquote(:u)(), do: 1\n\n  def run, do: u()\nend\n", myFixture.file.text)
    }

    fun testRenameAtTheAtomOfAnUnquotedNameRewritesItsAtom() {
        assertRenames(
            "g",
            "defmodule M do\n  def unquote(:<caret>f)(x), do: x\n\n  def run, do: f(1)\nend\n",
            "defmodule M do\n  def unquote(:g)(x), do: x\n\n  def run, do: g(1)\nend\n"
        )
    }

    fun testRenameAtACaptureOfAnUnquotedNameRewritesItsAtom() {
        assertRenames(
            "g",
            "defmodule M do\n  def unquote(:f)(x), do: x\n\n  def run, do: &<caret>f/1\nend\n",
            "defmodule M do\n  def unquote(:g)(x), do: x\n\n  def run, do: &g/1\nend\n"
        )
    }

    fun testRenameOfAnUnquotedNameRewritesItsSpec() {
        assertRenames(
            "g",
            "defmodule M do\n  @spec unquote(:f)(term) :: term\n  def unquote(:<caret>f)(x), do: x\nend\n",
            "defmodule M do\n  @spec unquote(:g)(term) :: term\n  def unquote(:g)(x), do: x\nend\n"
        )
    }

    fun testRenameAtACallOfAnUnquotedProtocolFunctionRewritesItsAtom() {
        assertRenames(
            "g",
            "defprotocol P do\n  def unquote(:f)(t)\nend\n\ndefmodule U do\n  def run, do: P.<caret>f(1)\nend\n",
            "defprotocol P do\n  def unquote(:g)(t)\nend\n\ndefmodule U do\n  def run, do: P.g(1)\nend\n"
        )
    }

    fun testRenameAtAnAtomNamingAnUnquotedImplementationRewritesItsAtom() {
        assertRenames(
            "g",
            "defprotocol P do\n  def f(t)\nend\n\ndefimpl P, for: Integer do\n  def unquote(:f)(t), do: t\nend\n\n" +
                "defmodule U do\n  def run, do: apply(P.Integer, :<caret>f, [1])\nend\n",
            "defprotocol P do\n  def g(t)\nend\n\ndefimpl P, for: Integer do\n  def unquote(:g)(t), do: t\nend\n\n" +
                "defmodule U do\n  def run, do: apply(P.Integer, :g, [1])\nend\n"
        )
    }

    fun testRenameAtACallOfAnUnquotedCallbackImplementationRewritesItsAtom() {
        assertRenames(
            "g",
            "defmodule B do\n  @callback f(term) :: term\nend\n\ndefmodule I do\n  @behaviour B\n\n" +
                "  def unquote(:f)(x), do: x\n  def run, do: <caret>f(1)\nend\n",
            "defmodule B do\n  @callback g(term) :: term\nend\n\ndefmodule I do\n  @behaviour B\n\n" +
                "  def unquote(:g)(x), do: x\n  def run, do: g(1)\nend\n"
        )
    }

    fun testRenameAtTheAtomOfAnUnquotedTypeRewritesItsAtom() {
        assertRenames(
            "v",
            "defmodule M do\n  @type unquote(:<caret>t)() :: atom\n  @type u :: t()\nend\n",
            "defmodule M do\n  @type unquote(:v)() :: atom\n  @type u :: v()\nend\n"
        )
    }

    fun testRenameAtTheAtomOfAnUnquotedCallbackRewritesItsAtom() {
        assertRenames(
            "d",
            "defmodule B do\n  @callback unquote(:<caret>c)() :: term\nend\n\ndefmodule I do\n  @behaviour B\n\n  def c, do: 1\nend\n",
            "defmodule B do\n  @callback unquote(:d)() :: term\nend\n\ndefmodule I do\n  @behaviour B\n\n  def d, do: 1\nend\n"
        )
    }

    fun testRenameAtTheAtomOfAnUnquotedDelegationRewritesItsAtom() {
        assertRenames(
            "f",
            "defmodule M do\n  defdelegate unquote(:<caret>d)(x), to: T, as: :e\n\n  def run, do: d(1)\nend\n",
            "defmodule M do\n  defdelegate unquote(:f)(x), to: T, as: :e\n\n  def run, do: f(1)\nend\n"
        )
    }

    fun testRenameAtTheUnquoteOfAnUnquotedDelegationRewritesItsAtom() {
        assertRenames(
            "f",
            "defmodule M do\n  defdelegate un<caret>quote(:d)(x), to: T, as: :e\n\n  def run, do: d(1)\nend\n",
            "defmodule M do\n  defdelegate unquote(:f)(x), to: T, as: :e\n\n  def run, do: f(1)\nend\n"
        )
    }

    fun testRenameAtTheUnquoteOfAnUnquotedProtocolFunctionRewritesItsAtom() {
        assertRenames(
            "g",
            "defprotocol P do\n  def un<caret>quote(:f)(t)\nend\n\ndefmodule U do\n  def run, do: P.f(1)\nend\n",
            "defprotocol P do\n  def unquote(:g)(t)\nend\n\ndefmodule U do\n  def run, do: P.g(1)\nend\n"
        )
    }

    fun testRenameAtTheUnquoteOfAnUnquotedCallbackRewritesItsAtom() {
        assertRenames(
            "d",
            "defmodule B do\n  @callback un<caret>quote(:c)() :: term\nend\n\ndefmodule I do\n  @behaviour B\n\n  def c, do: 1\nend\n",
            "defmodule B do\n  @callback unquote(:d)() :: term\nend\n\ndefmodule I do\n  @behaviour B\n\n  def d, do: 1\nend\n"
        )
    }

    /** `true`, `false` and `nil` are atoms written without `:`, so a rename of a head unquoting one writes the new atom whole. */
    fun testRenameOfAnUnquotedKeywordNameWritesAnAtom() {
        for (keyword in listOf("true", "false", "nil")) {
            for (caret in listOf("unquote(<caret>$keyword)", "M.<caret>$keyword(1)")) {
                assertRenames(
                    "g",
                    "defmodule M do\n  def unquote($keyword)(x), do: x\n\n  def run, do: M.$keyword(1)\nend\n"
                        .replace("unquote($keyword)", caret.takeIf { it.startsWith("unquote") } ?: "unquote($keyword)")
                        .replace("M.$keyword(1)", caret.takeIf { it.startsWith("M.") } ?: "M.$keyword(1)"),
                    "defmodule M do\n  def unquote(:g)(x), do: x\n\n  def run, do: M.g(1)\nend\n"
                )
            }
        }
    }

    /** A written `unquote(:f)(x)` call is not resolved, so a rename of `def unquote(:f)(x)` must leave it whole. */
    fun testRenameLeavesAWrittenUnquotedCallWhole() {
        myFixture.configureByText(
            "m.ex",
            "defmodule M do\n  def unquote(:<caret>f)(x), do: x\n\n  def run, do: unquote(:f)(1)\n\n" +
                "  defmacro m do\n    quote do\n      unquote(:f)(1)\n    end\n  end\nend\n"
        )
        myFixture.renameTargetAtCaret("g")

        assertTrue(myFixture.file.text, myFixture.file.text.contains("def unquote(:g)(x), do: x"))
        assertEquals(2, Regex("""unquote\(:[fg]\)\(1\)""").findAll(myFixture.file.text).count())
    }

    /** A head written `unquote(name)` has no name until `name` is bound, so nothing renames it, at any head kind. */
    fun testRenameLeavesAHeadUnquotingAVariable() {
        val heads = mapOf(
            "def" to "defmodule M do\n  for name <- [:a, :b] do\n    def un<caret>quote(name)(x), do: x\n  end\nend\n",
            "@callback" to "defmodule M do\n  for name <- [:a, :b] do\n    @callback un<caret>quote(name)(term) :: term\n  end\nend\n",
            "@type" to "defmodule M do\n  for name <- [:a, :b] do\n    @type un<caret>quote(name)() :: atom\n  end\nend\n",
            "protocol def" to "defprotocol P do\n  for name <- [:a, :b] do\n    def un<caret>quote(name)(t)\n  end\nend\n"
        )

        assertEquals(heads.keys.map { "$it: unchanged" }, heads.map { (kind, text) -> "$kind: ${renamedText(text)}" })
    }

    /** "unchanged" when every rename target at the caret in [text] leaves it as it is, else what renaming to `g` wrote. */
    private fun renamedText(text: String): String {
        myFixture.configureByText("m.ex", text)

        for (target in myFixture.renameTargetsAtCaret()) {
            runCatching { myFixture.renameTarget(target, "g") }
        }

        return myFixture.file.text.takeUnless { it == text.replace("<caret>", "") } ?: "unchanged"
    }

    /** Go To from an `unquote`-named callback implementation's `unquote` or atom reaches the `@callback`. */
    fun testGotoDeclarationFromAnUnquotedCallbackImplementationReachesItsCallback() {
        val text = "defmodule B do\n  @callback f(term) :: term\nend\n\ndefmodule I do\n  @behaviour B\n\n" +
            "  def unquote(:f)(x), do: x\nend\n"

        assertGotoLines(text, "un|quote(:f)", "@callback f(term) :: term")
        assertGotoLines(text, "unquote(:|f)", "@callback f(term) :: term")
    }

    /** Go To from an `unquote`-named implementation's `unquote` or atom reaches the protocol function. */
    fun testGotoDeclarationFromAnUnquotedImplementationReachesItsProtocolFunction() {
        val text = "defprotocol P do\n  def f(t)\nend\n\ndefimpl P, for: Integer do\n  def unquote(:f)(t), do: t\nend\n"

        assertGotoLines(text, "un|quote(:f)", "def f(t)")
        assertGotoLines(text, "unquote(:|f)", "def f(t)")
    }

    /** Go To with the caret where `|` is in [caret] in [text] lands only on the line [expected]. */
    private fun assertGotoLines(text: String, caret: String, expected: String) {
        myFixture.configureByText("m.ex", text)
        myFixture.editor.caretModel.moveToOffset(text.indexOf(caret.replace("|", "")) + caret.indexOf('|'))

        val lines = myFixture.gotoDeclarationTargetsAtCaret().orEmpty().mapNotNull { target ->
            target.destination?.textOffset?.let { offset -> text.lines()[text.take(offset).count { it == '\n' }].trim() }
        }

        assertEquals("from `$caret`", listOf(expected), lines)
    }

    /** Go To from a call lands on an `unquote`-named declaration's atom, where its name is. */
    fun testGotoDeclarationOfAnUnquotedNameLandsOnItsAtom() {
        myFixture.configureByText("m.ex", "defmodule M do\n  def unquote(:f)(x), do: x\n\n  def run, do: <caret>f(1)\nend\n")

        assertEquals(
            listOf(myFixture.file.text.indexOf(":f") + 1),
            myFixture.gotoDeclarationTargetsAtCaret()?.map { it.destination?.textOffset }
        )
    }

    /** Navigating to an `unquote`-named declaration, as the Structure view does, puts the caret on its atom. */
    fun testNavigationCaretOfAnUnquotedNameIsOnItsAtom() {
        val heads = mapOf(
            "def " to "defmodule M do\n  def unquote(:f)(x), do: x\nend\n",
            "@callback " to "defmodule M do\n  @callback unquote(:f)(term) :: term\nend\n",
            "@type " to "defmodule M do\n  @type unquote(:f)() :: atom\nend\n",
            "defdelegate " to "defmodule M do\n  defdelegate unquote(:f)(x), to: T\nend\n"
        )

        assertEquals(heads.keys.map { "${it.trim()}: on the atom" }, heads.map { (prefix, text) -> navigationCaret(text, prefix) })
    }

    /** Where navigating to the outermost call in [text] that starts with [prefix] puts the caret, against `:f`. */
    private fun navigationCaret(text: String, prefix: String): String {
        myFixture.configureByText("m.ex", text)
        val offset = ReadAction.nonBlocking(Callable {
            val declaration = PsiTreeUtil.findChildrenOfType(myFixture.file, Call::class.java).first { it.text.startsWith(prefix) }

            (PsiNavigationSupport.getInstance().getDescriptor(declaration) as OpenFileDescriptor).offset
        }).executeSynchronously()
        val atom = text.indexOf(":f")

        return if (offset in atom until atom + ":f".length) "${prefix.trim()}: on the atom" else "${prefix.trim()}: caret at $offset, atom at $atom"
    }

    private fun assertRenames(newName: String, before: String, after: String) {
        myFixture.configureByText("m.ex", before)
        myFixture.renameTargetAtCaret(newName)

        assertEquals(after, myFixture.file.text)
    }

    private fun eex() {
        myFixture.addFileToProject(
            "eex.ex",
            "defmodule EEx do\n  defmacro function_from_string(kind, name, source, args \\\\ [], options \\\\ []) do\n" +
                "    {kind, name, source, args, options}\n  end\nend\n"
        )
    }

    private fun generator() {
        myFixture.addFileToProject(
            "generator.ex",
            "defmodule Mix.Generator do\n  defmacro embed_template(name, contents) do\n    {name, contents}\n  end\nend\n"
        )
    }

    private fun target() {
        myFixture.addFileToProject("t.ex", "defmodule T do\n  def snoc(q, x), do: [x | q]\nend\n")
    }

    /** Ctrl+Click shows usages, and Find Usages from the name lists [use]. */
    private fun assertDeclares(use: String) {
        myFixture.assertShowUsagesChosenAtCaret()
        assertTrue("Find Usages: ${uses()}", use in uses())
    }

    /** Find Usages from the first parameter `name` after the caret lists exactly [expected]. */
    private fun assertNameIsUsed(vararg expected: String) {
        val text = myFixture.file.text
        myFixture.editor.caretModel.moveToOffset(text.indexOf("name", myFixture.caretOffset) + 1)

        assertEquals(expected.toList(), uses())
    }

    private fun uses(): List<String> =
        myFixture.psiUsagesAtCaret(project).filterNot { it.declaration }.map { usage ->
            val text = usage.file.viewProvider.document?.text ?: usage.file.text
            "${usage.file.name}:${text.substring(0, usage.range.startOffset).count { it == '\n' } + 1}"
        }.sorted()
}
