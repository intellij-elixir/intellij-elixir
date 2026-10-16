package org.elixir_lang.code_insight.completion.code_fragment

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.common.runAll
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.DefinitionDeclaration
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.stub.type.call.Stub

/** How the plugin's side of a point is observed: what [kind] reads an item as, and the [heads] at the dummy. */
class ObservationTest : PlatformTestCase() {
    private var autocompleteOnCodeCompletion = true

    override fun getProjectDescriptor(): LightProjectDescriptor = CodeFragmentProjectDescriptor

    override fun setUp() {
        super.setUp()

        val settings = CodeInsightSettings.getInstance()
        autocompleteOnCodeCompletion = settings.AUTOCOMPLETE_ON_CODE_COMPLETION
        settings.AUTOCOMPLETE_ON_CODE_COMPLETION = false
    }

    override fun tearDown() {
        runAll(
            { CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = autocompleteOnCodeCompletion },
            { super.tearDown() },
        )
    }

    fun testKindNamesANestedModuleInFull() {
        myFixture.configureByText("nested.ex", "defmodule Outer do\n  defmodule Inner do\n    def f, do: 1\n  end\nend\n")
        val calls = PsiTreeUtil.collectElementsOfType(myFixture.file, Call::class.java)
        val inner = calls.single { Stub.isModular(it) && it.text.startsWith("defmodule Inner") }
        val f = calls.single { DefinitionDeclaration.`is`(it) }

        assertEquals(Kind.MODULE to "Outer.Inner", kind(inner, Expr))
        assertEquals(Kind.CALL to "Outer.Inner", kind(f, Expr))
    }

    fun testKindNamesAnErlangModule() {
        myFixture.configureByText("erlang.ex", ":lis<caret>")
        myFixture.complete(CompletionType.BASIC)
        val kinds = ReadAction.computeBlocking<List<Pair<Kind, String?>>, Throwable> {
            myFixture.lookupElements.orEmpty().map { kind(it.psiElement, UnquotedAtom("lis")) }
        }

        assertTrue("$kinds", Kind.MODULE to ":lists" in kinds)
    }

    /** One completion copy per form the dummy lands in, each in a function body. */
    fun testHeadsAtTheDummy() {
        CompletionRecorder.register(testRootDisposable)
        val body = listOf("def/2@1", "[]@0", "{}/2@1")
        val interpolation = body + listOf("=/2@1", "<<>>/2@1", "::/2@0", ".to_string/1@0")

        val expected = mapOf(
            "x = \"a#{x<caret>}\"" to interpolation,
            "lo<caret>cal(1)" to body,
            "Hello.World.wo<caret>r(1)" to body,
            "Hello.Wo<caret>rld" to body,
            ":fo<caret>o" to body,
            "x = \"string #{1 + 2<caret>}\"" to interpolation + listOf("+/2@1", ".to_integer/2@0"),
            "[~s(a)<caret>]" to body + "[]@0",
            "{x}<caret> = {1}" to body,
        )
        val actual = expected.keys.withIndex().associate { (index, form) -> form to headsAt(index, form) }

        assertEquals(expected, actual)
    }

    private fun headsAt(index: Int, form: String): List<String> {
        myFixture.configureByText("form$index.ex", "def f do\n  $form\nend\n")
        val caret = myFixture.caretOffset
        val recorded = CompletionRecorder.record(form) { myFixture.complete(CompletionType.BASIC) }
        LookupManager.getInstance(project).hideActiveLookup()

        return heads(recorded.copy, caret, recorded.dummyIdentifier).map(Step::shown)
    }
}
