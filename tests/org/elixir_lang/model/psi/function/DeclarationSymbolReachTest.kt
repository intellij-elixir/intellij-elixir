package org.elixir_lang.model.psi.function

import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.renameTargetsAtCaret

/**
 * A `defdelegate` or EEx function is reached as a clause's is: a capture of it and an `import only:` key naming it
 * resolve to its [FunctionSymbol], so Find Usages and rename start from there too. A clause is the control.
 */
class DeclarationSymbolReachTest : PlatformTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/psi/callable_declaration"

    fun testCapturesAndImportKeysReachEveryDeclaringForm() {
        myFixture.copyFileToProject("eex.ex")
        myFixture.configureByText(
            "reach.ex",
            """
            defmodule Targets do
              require EEx

              def clause(a), do: a
              defdelegate delegated(a), to: Kernel, as: :is_atom
              EEx.function_from_string(:def, :rendered, "", [:a])
            end

            defmodule Caller do
              import Targets, only: [clause: 1, delegated: 1, rendered: 1]

              def captures, do: [&Targets.clause/1, &Targets.delegated/1, &Targets.rendered/1]
            end
            """.trimIndent()
        )

        val text = myFixture.file.text
        val places = listOf(
            "&Targets.clause/1" to "clause", "&Targets.delegated/1" to "delegated", "&Targets.rendered/1" to "rendered",
            "clause: 1" to "clause", "delegated: 1" to "delegated", "rendered: 1" to "rendered"
        )
        val actual = places.joinToString("\n") { (place, name) ->
            myFixture.editor.caretModel.moveToOffset(text.indexOf(place) + place.indexOf(name) + 1)
            val symbols = myFixture.renameTargetsAtCaret().filterIsInstance<FunctionSymbol>().map { "${it.name}/${it.arity}" }

            "$place -> $symbols"
        }

        assertEquals(
            """
            &Targets.clause/1 -> [clause/1]
            &Targets.delegated/1 -> [delegated/1]
            &Targets.rendered/1 -> [rendered/1]
            clause: 1 -> [clause/1]
            delegated: 1 -> [delegated/1]
            rendered: 1 -> [rendered/1]
            """.trimIndent(),
            actual
        )
    }
}
