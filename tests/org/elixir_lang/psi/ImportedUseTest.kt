package org.elixir_lang.psi

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.completionAttemptAtCaret
import org.elixir_lang.inspection.References
import org.elixir_lang.psi.call.Call

/**
 * An `import` brings in what a `use` injects into the imported module, as Elixir 1.20 does: public definitions of
 * the `__using__` quote, a `use` inside it included, filtered by `only:` and `except:`. An `import` inside the quote
 * is the module's own and does not reach the importer.
 */
class ImportedUseTest : PlatformTestCase() {
    fun testImportBringsInWhatAUseInjects() {
        val rows = listOf(
            "import Host" to "injected",
            "import Host, only: [injected: 1]" to "injected",
            "import Host, except: [injected: 1]" to "injected",
            "import Host, only: [chained: 1]" to "injected",
            "import Host" to "chained",
            "import Host, except: [injected: 1]" to "chained",
            "import Host" to "hidden",
            "import Host" to "helper",
        )

        val actual = rows.joinToString("\n") { (import, name) -> "$import; $name(a): ${outcome(import, name)}" }

        assertEquals(
            """
            import Host; injected(a): def injected(x), do: {:injected, x}
            import Host, only: [injected: 1]; injected(a): def injected(x), do: {:injected, x}
            import Host, except: [injected: 1]; injected(a): Does not resolve to anything
            import Host, only: [chained: 1]; injected(a): Does not resolve to anything
            import Host; chained(a): def chained(x), do: {:chained, x}
            import Host, except: [injected: 1]; chained(a): def chained(x), do: {:chained, x}
            import Host; hidden(a): Does not resolve to anything
            import Host; helper(a): Does not resolve to anything
            """.trimIndent(),
            actual
        )
    }

    /** A head with defaults and its clause in the quote are one function, reached whole as the module's own are. */
    fun testAnInjectedFunctionWithDefaultsIsReachedWhole() {
        val whole = """def pad(a, b \\ 1) | def pad(a, b), do: {a, b}"""

        assertEquals("imported: $whole", "imported: " + outcome("import Host", "pad"))
        assertEquals("local: $whole", "local: " + resolved(MODULES, MODULES.indexOf("do: pad(a)") + "do: ".length))
    }

    fun testCompletionAfterAnImportOffersWhatAUseInjects() {
        myFixture.configureByText("importer.ex", "$MODULES\n\ndefmodule Importer do\n  import Host\n  def calls(a), do: inj<caret>\nend\n")

        val attempt = myFixture.completionAttemptAtCaret()

        assertTrue(
            "expected `injected` to be offered or inserted, got $attempt",
            attempt.candidates?.contains("injected") ?: attempt.text.contains("do: injected")
        )
    }

    private fun outcome(import: String, name: String): String {
        val text = "$MODULES\n\ndefmodule Importer do\n  $import\n  def calls(a), do: $name(a)\nend\n"
        myFixture.configureByText("importer.ex", text)
        myFixture.enableInspections(References())

        val callOffset = text.lastIndexOf("$name(a)")
        val errors = myFixture.doHighlighting(HighlightSeverity.ERROR)
            .filter { it.startOffset == callOffset }
            .mapNotNull { it.description }
            .distinct()

        return (errors + validTargets(callOffset)).joinToString(" | ")
    }

    /** The first line of each valid result of the call at [callOffset] in [text]. */
    private fun resolved(text: String, callOffset: Int): String {
        myFixture.configureByText("host.ex", text)

        return validTargets(callOffset).joinToString(" | ")
    }

    private fun validTargets(callOffset: Int): List<String> {
        val call = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(callOffset), Call::class.java)!!

        return (call.reference as PsiPolyVariantReference).multiResolve(false)
            .filter { it.isValidResult }
            .mapNotNull { it.element?.text?.lines()?.first()?.trim() }
            .distinct()
    }

    private companion object {
        val MODULES = """
            defmodule Helpers do
              def helper(x), do: {:helper, x}
            end

            defmodule Inner do
              defmacro __using__(_) do
                quote do
                  def chained(x), do: {:chained, x}
                end
              end
            end

            defmodule Injector do
              defmacro __using__(_) do
                quote do
                  use Inner
                  import Helpers
                  def injected(x), do: {:injected, x}
                  defp hidden(x), do: x
                  def pad(a, b \\ 1)
                  def pad(a, b), do: {a, b}
                end
              end
            end

            defmodule Host do
              use Injector
              def local(a), do: pad(a)
            end
        """.trimIndent()
    }
}
