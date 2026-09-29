package org.elixir_lang.structure_view

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.psi.PsiElement
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.structure_view.element.CallDefinition

/** Each clause form builds a node of its kind; a guard is a macro, as Elixir defines it with `defmacro`. */
class CallDefinitionKindNodeTest : PlatformTestCase() {
    fun testEveryClauseFormBuildsANodeOfItsKind() {
        myFixture.configureByText(
            "kinds.ex",
            """
            defmodule Kinds do
              def public_function(a), do: a
              defp private_function(a), do: a
              defmacro public_macro(a), do: a
              defmacrop private_macro(a), do: a
              defguard public_guard(a) when a > 0
              defguardp private_guard(a) when a > 0
            end
            """.trimIndent()
        )

        val nodes = mutableListOf<CallDefinition>()

        fun walk(element: StructureViewTreeElement) {
            if (element is CallDefinition) nodes.add(element)

            for (child in element.children) {
                if (child is StructureViewTreeElement) walk(child)
            }
        }

        walk(Model(myFixture.file as ElixirFile, null).root)

        assertEquals(
            """
            public_function/1 RUN PUBLIC
            private_function/1 RUN PRIVATE
            public_macro/1 COMPILE PUBLIC
            private_macro/1 COMPILE PRIVATE
            public_guard/1 COMPILE PUBLIC
            private_guard/1 COMPILE PRIVATE
            """.trimIndent(),
            nodes.sortedBy { (it.value as? PsiElement)?.textOffset ?: -1 }
                .joinToString("\n") { "${it.name} ${it.time()} ${it.visibility()}" }
        )
    }
}
