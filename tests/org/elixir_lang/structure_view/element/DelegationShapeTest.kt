package org.elixir_lang.structure_view.element

import org.elixir_lang.junit.LightTestCase
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.call.Call
import org.elixir_lang.structure_view.element.Delegation.Companion.callDefinitionHeadCallList
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.structure_view.Model

// If Delegation.is accepts a call, reading its head list must not throw - callDefinitionHeadCallList()
// is also on the variable-resolution path, not just the structure view.
class DelegationShapeTest : LightTestCase() {
    private data class Shape(val label: String, val source: String)

    private fun failureFor(shape: Shape): String? {
        myFixture.configureByText("delegation_${shape.label}.ex", shape.source)

        return PsiTreeUtil
            .findChildrenOfType(myFixture.file as ElixirFile, Call::class.java)
            .filter { call -> Delegation.`is`(call) }
            .firstNotNullOfOrNull { call ->
                try {
                    callDefinitionHeadCallList(call)
                    null
                } catch (throwable: Throwable) {
                    "${shape.label}: ${throwable.javaClass.name}: ${throwable.message} @ " +
                        throwable.stackTrace.take(2).joinToString(" <- ") {
                            "${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})"
                        }
                }
            }
    }

    fun testAcceptedDelegationShapesReadTheirHeadListWithoutThrowing() {
        val shapes = listOf(
            // The ordinary forms, as controls.
            Shape("to", "defmodule A do\n  defdelegate foo(a), to: B\nend\n"),
            Shape("to_as", "defmodule A do\n  defdelegate foo(a), to: B, as: :bar\nend\n"),
            Shape("list", "defmodule A do\n  defdelegate [foo(a), bar(b)], to: B\nend\n"),
            // A do block and a pipe each raise the resolved arity without writing an argument, so these
            // are the shapes where the gate's count and the body's count come apart.
            Shape("do_block", "defmodule A do\n  defdelegate foo(a) do\n    :ok\n  end\nend\n"),
            Shape("piped", "defmodule A do\n  x |> defdelegate(foo(a))\nend\n"),
            Shape("piped_do_block", "defmodule A do\n  x |> defdelegate do\n    :ok\n  end\nend\n"),
            Shape("bare_do_block", "defmodule A do\n  defdelegate do\n    :ok\n  end\nend\n"),
        )

        val failures = shapes.mapNotNull { shape -> failureFor(shape) }

        assertEquals(
            "reading the head list threw for:\n" + failures.joinToString("\n"),
            emptyList<String>(),
            failures,
        )
    }

    /** `append_first: :true` and `:"true"` are `append_first: true`: the atom is the same. */
    fun testAppendFirstIsReadFromTheAtomItQuotesTo() {
        val expected = listOf(
            "true" to true,
            ":true" to true,
            ":\"true\"" to true,
            ":\"tru\\x65\"" to true,
            "false" to false,
            ":false" to false,
            ":\"false\"" to false,
            "nil" to false,
        )

        val actual = expected.map { (value, _) ->
            myFixture.configureByText("append_first.ex", "defmodule A do\n  defdelegate foo(a), to: B, append_first: $value\nend\n")

            val delegations = mutableListOf<Delegation>()

            fun walk(element: StructureViewTreeElement) {
                if (element is Delegation) delegations.add(element)

                for (child in element.children) {
                    if (child is StructureViewTreeElement) walk(child)
                }
            }

            walk(Model(myFixture.file as ElixirFile, null).root)

            value to delegations.single().appendFirst()
        }

        assertEquals(expected, actual)
    }
}
