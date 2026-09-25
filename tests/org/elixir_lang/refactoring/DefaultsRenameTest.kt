package org.elixir_lang.refactoring

import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.renameTargetAtCaret

/**
 * A definition with defaults is one function at each arity it declares, so renaming it from a use at one arity renames
 * every use at the others: captures, `@spec`s, `apply/3` and MFA atoms, as well as calls.
 */
class DefaultsRenameTest : PlatformTestCase() {
    private val source = """
        defmodule Definer do
          @spec snoc(term) :: term
          @spec snoc(term, term) :: term
          def snoc(q, x \\ nil), do: {q, x}
        end

        defmodule Caller do
          def calls(a, b), do: {Definer.snoc(a, b), Definer.snoc(a), &Definer.snoc/1, apply(Definer, :snoc, [a]), {Definer, :snoc, 1}}
        end
    """.trimIndent()

    /** A `@spec` names one arity, so it is one rename target whichever arity of the function with defaults it is written at. */
    fun testRenamingFromASpecRenamesTheWholeFunction() {
        val delegated = source
            .replace("def snoc(q, x \\\\ nil), do: {q, x}", "defdelegate snoc(q, x \\\\ nil), to: Target")
            .replace("defmodule Definer do", "defmodule Target do\n  def snoc(q, x), do: {q, x}\nend\n\ndefmodule Definer do")

        // Renaming a delegation keeps what it delegates to: `as:` pins the target's name.
        val renamedDelegation = delegated
            .replace("snoc", "renamed")
            .replace("def renamed(q, x), do: {q, x}", "def snoc(q, x), do: {q, x}")
            .replace("to: Target", "to: Target, as: :snoc")

        for ((description, text, expected) in listOf(
            Triple("a def", source, source.replace("snoc", "renamed")),
            Triple("a defdelegate", delegated, renamedDelegation)
        )) {
            for (spec in listOf("@spec snoc(term) :: term", "@spec snoc(term, term) :: term")) {
                myFixture.configureByText("defaults_spec_rename.ex", text.replace(spec, spec.replace("snoc", "sn<caret>oc")))

                myFixture.renameTargetAtCaret("renamed")

                assertEquals("$description, $spec", expected, myFixture.editor.document.text)
            }
        }
    }

    /** An `import only:`/`except:` key naming either arity names the function, so a rename renames it. */
    fun testRenamingRenamesImportKeysAtEitherArity() {
        val imports = source + """


            defmodule OnlyCaller do
              import Definer, only: [snoc: 1]

              def calls(a), do: snoc(a)
            end

            defmodule ExceptCaller do
              import Definer, except: [snoc: 2]

              def calls(a), do: snoc(a)
            end
        """.trimIndent()
        myFixture.configureByText("defaults_import_rename.ex", imports.replace("Definer.snoc(a, b)", "Definer.sn<caret>oc(a, b)"))

        myFixture.renameTargetAtCaret("renamed")

        assertEquals(imports.replace("snoc", "renamed"), myFixture.editor.document.text)
    }

    /**
     * A callback or protocol function is implemented by a function, whose clauses after a head with defaults are it too,
     * so renaming the callback or protocol function renames them all.
     */
    fun testRenamingACallbackOrProtocolFunctionRenamesEveryClauseOfItsImplementation() {
        val implementation = "  def snoc(q, x \\\\ nil)\n  def snoc(q, x), do: {q, x}\n"
        val sources = listOf(
            "a callback" to "defmodule Behaviour do\n  @callback sn<caret>oc(term) :: term\nend\n\n" +
                "defmodule Implementation do\n  @behaviour Behaviour\n\n$implementation\nend\n",
            "a protocol function" to "defprotocol Snocable do\n  def sn<caret>oc(q)\nend\n\n" +
                "defimpl Snocable, for: List do\n$implementation\nend\n"
        )

        for ((description, text) in sources) {
            myFixture.configureByText("implementation_rename.ex", text)

            myFixture.renameTargetAtCaret("renamed")

            assertEquals(description, text.replace("<caret>", "").replace("snoc", "renamed"), myFixture.editor.document.text)
        }
    }

    fun testRenamingFromTheHighestArityRenamesUsesAtTheLower() {
        myFixture.configureByText("defaults_rename.ex", source.replace("Definer.snoc(a, b)", "Definer.sn<caret>oc(a, b)"))

        myFixture.renameTargetAtCaret("renamed")

        assertEquals(source.replace("snoc", "renamed"), myFixture.editor.document.text)
    }
}
