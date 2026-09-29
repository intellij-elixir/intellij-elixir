package org.elixir_lang.annotator

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.elixir_lang.ElixirFileType
import org.elixir_lang.psi.ElixirIdentifier

/**
 * What [Parameter.putParameterized] makes of each definition form's name and its head's parameter: the name is the
 * function or macro it defines, the parameter is a variable. A guard's name is a macro name - Elixir implements
 * `defguard` as `define_guard(:defmacro, ...)` - and a guarded `def ... when ...` is the control that the `when` does
 * not change the answer.
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
            guard_clause -> MACRO_NAME
            private_guard_clause -> MACRO_NAME
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
