package org.elixir_lang.annotator

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.elixir_lang.ElixirFileType
import org.elixir_lang.psi.ElixirIdentifier

/**
 * What [Parameter.putParameterized] makes of each definition form's name and its head's parameter: the name is the
 * function or macro it defines, the parameter is a variable. A form that answers `null` for both is invisible to
 * everything downstream - `ElementDescriptionProvider` gives it no "function" type in Find Usages,
 * `PresentationImpl` no presentation, `call_definition_clause/Variants` no completion.
 *
 * `defguard` is such a form. [Parameter] asks `CallDefinitionClause.isFunction` then `isMacro`, but
 * `CallDefinitionClause.is` is `isFunction || isMacro || isGuard`, so a guard falls through both into generic
 * handling. A guarded `def ... when ...` is here as the control that the `when` is not what does it.
 *
 * `FUNCTION_NAME` for a guard, not `MACRO_NAME`: the plugin already classifies guards as run-time in an explicit arm
 * beside macros, at `structure_view/element/CallDefinitionClause.time`.
 */
class ParameterTest : BasePlatformTestCase() {
    fun testEveryDefinitionFormTypesItsNameAndItsParameter() {
        myFixture.configureByText(
            ElixirFileType.INSTANCE,
            """
            defmodule Sample do
              def function_clause(function_parameter), do: function_parameter
              def guarded_function_clause(guarded_parameter) when guarded_parameter > 0, do: guarded_parameter
              defmacro macro_clause(macro_parameter), do: macro_parameter
              defguard guard_clause(guard_parameter) when guard_parameter > 0
              defguardp private_guard_clause(private_guard_parameter) when private_guard_parameter > 0
              defdelegate delegated(delegated_parameter), to: Target
            end
            """.trimIndent()
        )

        val actual = NAMES.joinToString("\n") { "$it -> ${typeOf("$it(")}" } + "\n" +
            PARAMETERS.joinToString("\n") { "$it -> ${typeOf("$it)")}" }

        assertEquals(
            """
            function_clause -> FUNCTION_NAME
            guarded_function_clause -> FUNCTION_NAME
            macro_clause -> MACRO_NAME
            guard_clause -> FUNCTION_NAME
            private_guard_clause -> FUNCTION_NAME
            delegated -> FUNCTION_NAME
            function_parameter -> VARIABLE
            guarded_parameter -> VARIABLE
            macro_parameter -> VARIABLE
            guard_parameter -> VARIABLE
            private_guard_parameter -> VARIABLE
            delegated_parameter -> VARIABLE
            """.trimIndent(),
            actual
        )
    }

    /** [needle] includes the delimiter that picks the head's occurrence out of the ones in the body. */
    private fun typeOf(needle: String): String {
        val offset = myFixture.file.text.indexOf(needle)
        assertTrue("'$needle' not found in the configured source", offset >= 0)

        val leaf = myFixture.file.findElementAt(offset)!!
        val identifier = PsiTreeUtil.getParentOfType(leaf, ElixirIdentifier::class.java)
            ?: return "not an ElixirIdentifier (${leaf.parent.javaClass.simpleName})"

        return "${Parameter.putParameterized(Parameter(identifier)).type}"
    }

    private companion object {
        val NAMES = listOf(
            "function_clause",
            "guarded_function_clause",
            "macro_clause",
            "guard_clause",
            "private_guard_clause",
            "delegated"
        )
        val PARAMETERS = listOf(
            "function_parameter",
            "guarded_parameter",
            "macro_parameter",
            "guard_parameter",
            "private_guard_parameter",
            "delegated_parameter"
        )
    }
}
