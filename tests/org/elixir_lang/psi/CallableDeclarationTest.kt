package org.elixir_lang.psi

import com.intellij.psi.ResolveState
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.call.macroChildCallSequence

/**
 * Every call in a module body, with the form [CallableDeclaration] says it declares - by [CallableDeclaration.formOf]
 * and by [CallableDeclaration.syntacticFormOf] - and the name/arity it puts in scope. The whole table is compared at
 * once so a failure shows every row that disagrees, not the first.
 */
class CallableDeclarationTest : PlatformTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/psi/callable_declaration"

    fun testEveryModuleBodyCallAnswersTheOneQuestion() {
        myFixture.configureByFiles("forms.ex", "eex.ex", "mix_generator.ex")

        val module = myFixture.file.children.filterIsInstance<Call>().single()
        val state = ResolveState.initial()
        val actual = module.macroChildCallSequence().joinToString("\n") { call ->
            val form = CallableDeclaration.formOf(call, state)
            val syntacticForm = CallableDeclaration.syntacticFormOf(call)
            val declarations = form?.let { CallableDeclaration.declarations(call, it, state) }.orEmpty()

            ("${call.text.lineSequence().first()} -> ${form ?: "-"} | ${syntacticForm ?: "-"} " +
                declarations.joinToString(", ") { render(it) }).trimEnd()
        }

        assertEquals(
            """
            require EEx -> - | -
            require Mix.Generator -> - | -
            def public_function(a), do: a -> CLAUSE | CLAUSE public_function/1
            defp private_function(a, b \\ 1), do: {a, b} -> CLAUSE | CLAUSE private_function/1..2
            defmacro public_macro(a), do: a -> CLAUSE | CLAUSE public_macro/1
            defmacrop private_macro(a), do: a -> CLAUSE | CLAUSE private_macro/1
            defguard is_small(a) when a < 10 -> CLAUSE | CLAUSE is_small/1
            defguardp is_large(a) when a > 10 -> CLAUSE | CLAUSE is_large/1
            @callback function_callback(integer) :: atom -> CALLBACK | CALLBACK function_callback/1
            @macrocallback macro_callback(term) :: Macro.t() -> CALLBACK | CALLBACK macro_callback/1
            defdelegate delegated(a, b), to: Target -> DELEGATION | DELEGATION delegated/2
            defexception [:message] -> EXCEPTION | EXCEPTION exception/1, message/1
            EEx.function_from_string(:def, :from_string, "<%= a %>", [:a]) -> EEX_FUNCTION_FROM | - from_string/1
            EEx.function_from_file(:defp, :from_file, "sample.eex") -> EEX_FUNCTION_FROM | - from_file/0
            Mix.Generator.embed_template(:log, "Log") -> GENERATOR_EMBED | - log_template/1
            Mix.Generator.embed_text(:error, "Error") -> GENERATOR_EMBED | - error_text/0
            EEx.function_from_string(:def, :"quoted_from_string", "") -> EEX_FUNCTION_FROM | - quoted_from_string/0
            EEx.function_from_string(:def, :"#{:interpolated}_from_string", "") -> EEX_FUNCTION_FROM | -
            Mix.Generator.embed_text(:"quoted", "Quoted") -> GENERATOR_EMBED | - quoted_text/0
            import Enum -> - | -
            alias Target, as: T -> - | -
            use Target -> - | -
            for x <- [1], do: x -> - | -
            if true, do: :ok -> - | -
            quote do: 1 -> - | -
            @moduledoc false -> - | -
            """.trimIndent(),
            actual
        )
    }

    /**
     * `headBindingFormOf ⊆ syntacticFormOf ⊆ formOf`, and [CallableDeclaration.syntacticFormOf] never answers a form
     * that needed a reference resolved. Every call in the fixtures, not just the module body's, and every violation
     * is collected rather than asserted so one bad row does not hide the rest.
     */
    fun testTheThreeEntryPointsNest() {
        myFixture.configureByFiles("forms.ex", "eex.ex", "mix_generator.ex")

        val state = ResolveState.initial()
        val violations = PsiTreeUtil.findChildrenOfType(myFixture.file, Call::class.java).mapNotNull { call ->
            val headBindingForm = CallableDeclaration.headBindingFormOf(call)
            val syntacticForm = CallableDeclaration.syntacticFormOf(call)
            val form = CallableDeclaration.formOf(call, state)
            val source = call.text.lineSequence().first()

            when {
                headBindingForm != null && syntacticForm != headBindingForm ->
                    "$source: headBindingFormOf $headBindingForm, syntacticFormOf ${syntacticForm ?: "-"}"
                syntacticForm != null && form != syntacticForm ->
                    "$source: syntacticFormOf $syntacticForm, formOf ${form ?: "-"}"
                syntacticForm?.requiresResolution == true ->
                    "$source: syntacticFormOf answered $syntacticForm, which formOf recognises only by resolving"
                else -> null
            }
        }

        assertEquals("", violations.joinToString("\n"))
    }

    private fun render(declaration: CallableDeclaration.Declaration): String {
        val interval = declaration.arityInterval ?: return "${declaration.name}/?"
        val maximum = interval.maximum

        return when (maximum) {
            interval.minimum -> "${declaration.name}/${interval.minimum}"
            null -> "${declaration.name}/${interval.minimum}.."
            else -> "${declaration.name}/${interval.minimum}..$maximum"
        }
    }
}

/** Whether [CallableDeclaration.formOf] must resolve a reference to recognise the form; exhaustive, so a new form must say. */
private val CallableDeclaration.Form.requiresResolution: Boolean
    get() = when (this) {
        CallableDeclaration.Form.CLAUSE,
        CallableDeclaration.Form.DELEGATION,
        CallableDeclaration.Form.CALLBACK,
        CallableDeclaration.Form.EXCEPTION -> false
        CallableDeclaration.Form.EEX_FUNCTION_FROM,
        CallableDeclaration.Form.GENERATOR_EMBED -> true
    }
