package org.elixir_lang.psi.scope.call_definition_clause

import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.ResolveState
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.declaration.Declaration
import org.elixir_lang.declaration.Declared
import org.elixir_lang.psi.call.Call
import org.elixir_lang.snapshot.text

/**
 * What each declaring form declares, written `atom/arity visibility presentation form`; `(no atom)` is a name with no
 * atom value, which declares nothing.
 */
class DeclarationsTest : PlatformTestCase() {
    fun testPlainHead() = assertDeclares("def plain(", "plain/1 public function clause")

    fun testGuardedHead() = assertDeclares("def plain_guarded(", "plain_guarded/1 public function clause")

    fun testUnquoteLiteralHead() = assertDeclares("def unquote(:literal)", "literal/1 public function clause")

    fun testUnquoteQuotedLiteralHead() =
        assertDeclares("def unquote(:\"quoted literal\")", "quoted literal/1 public function clause")

    fun testUnquoteVariableHeadHasNoAtom() = assertDeclares("def unquote(name)", "(no atom)")

    fun testUnquoteBooleanHeadNamesItsAtom() = assertDeclares("def unquote(true)", "true/1 public function clause")

    fun testOperatorHead() = assertDeclares("def left <~> right", "<~>/2 public function clause")

    fun testUnicodeHead() = assertDeclares("def café(", "café/1 public function clause")

    fun testQuestionHead() = assertDeclares("def plain?(", "plain?/1 public function clause")

    fun testBangHead() = assertDeclares("def plain!(", "plain!/1 public function clause")

    fun testDefaultsGiveARange() = assertDeclares("defp private(", "private/1..2 private function clause")

    fun testMacroClause() = assertDeclares("defmacro macro(", "macro/1 public macro clause")

    fun testCallbackIsAFunction() = assertDeclares("@callback cb(", "cb/1 public function callback")

    fun testMacrocallbackIsAMacro() = assertDeclares("@macrocallback mcb(", "mcb/1 public macro callback")

    fun testUnquoteLiteralCallback() =
        assertDeclares("@callback unquote(:cb_literal)", "cb_literal/1 public function callback")

    fun testDelegationTakesItsHeadsArities() =
        assertDeclares("defdelegate del(", "del/1..2 public function delegation")

    fun testUnquoteLiteralDelegation() =
        assertDeclares("defdelegate unquote(:del_literal)", "del_literal/1 public function delegation")

    fun testExceptionDeclaresExceptionAndMessage() =
        assertDeclares(
            "defexception",
            "exception/1 public function exception",
            "message/1 public function exception"
        )

    fun testEExArgumentsOmittedIsArityZero() =
        assertDeclares("EEx.function_from_string(:def, :eex_plain", "eex_plain/0 public function eex_function_from")

    fun testEExDefpIsPrivateWithItsArgumentsListLength() =
        assertDeclares(
            "EEx.function_from_string(:defp, :eex_private",
            "eex_private/2 private function eex_function_from"
        )

    fun testEExArgumentsNotALiteralListIsUnknown() =
        assertDeclares(
            "EEx.function_from_string(:def, :eex_unknown_args",
            "eex_unknown_args/? public function eex_function_from"
        )

    fun testEExQuotedName() =
        assertDeclares(
            "EEx.function_from_string(:def, :\"eex quoted\"",
            "eex quoted/0 public function eex_function_from"
        )

    fun testEExSingleQuotedName() =
        assertDeclares(
            "EEx.function_from_string(:def, :'eex single'",
            "eex single/0 public function eex_function_from"
        )

    fun testEExInterpolatedNameHasNoAtom() =
        assertDeclares("EEx.function_from_string(:def, :\"eex_#", "(no atom)")

    fun testEExKindNotALiteralIsUndecided() =
        assertDeclares("EEx.function_from_string(@kind", "eex_kind/1 undecided function eex_function_from")

    /** `embed_template` defines `name_template/1` alone; the walk resolves `name_template()` too, so 0..1 is kept. */
    fun testEmbedTemplateKeepsTheWalksArities() =
        assertDeclares(
            "Mix.Generator.embed_template(:embed_plain",
            "embed_plain_template/0..1 private function generator_embed"
        )

    fun testEmbedText() =
        assertDeclares(
            "Mix.Generator.embed_text(:embed_plain",
            "embed_plain_text/0 private function generator_embed"
        )

    fun testEmbedQuotedName() =
        assertDeclares(
            "Mix.Generator.embed_text(:\"embed quoted\"",
            "embed quoted_text/0 private function generator_embed"
        )

    private fun assertDeclares(lineStart: String, vararg expected: String) {
        myFixture.copyDirectoryToProject("declarations", "")
        val file = myFixture.configureFromTempProjectFile("heads.ex")
        val text = PsiDocumentManager.getInstance(project).getDocument(file)!!.text
        val offset = text.indexOf(lineStart).also { assertTrue("`$lineStart` not in heads.ex", it >= 0) }
        val call = PsiTreeUtil.findChildrenOfType(file, Call::class.java).first { it.textRange.startOffset == offset }
        val state = ResolveState.initial()
        val form = DeclaringForm.syntacticForm(call) ?: DeclaringForm.resolvingForm(call, state)
        assertNotNull("`$lineStart` declares nothing", form)

        assertEquals(expected.toList(), Declarations.of(form!!, call, state).map { describe(it.declaration) })
    }

    private fun describe(declaration: Declaration?): String {
        if (declaration == null) return "(no atom)"
        val arity = declaration.arity.text()
        val capabilities = declaration.capabilities
        val form = (declaration.declared as Declared.Source).form

        return "${declaration.name}/$arity ${capabilities.visibility.name.lowercase()} " +
            "${capabilities.presentation.name.lowercase()} ${form.name.lowercase()}"
    }

    override fun getTestDataPath(): String = "testData/org/elixir_lang/psi/scope/call_definition_clause"
}
