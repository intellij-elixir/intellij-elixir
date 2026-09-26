package org.elixir_lang.psi

import com.intellij.psi.PsiPolyVariantReference
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.call.Call

/**
 * A reference to each declaring form, from inside a later `def`, resolves to the call that declared it - both where
 * the declaration is written in the module and where a `use`d module's `quote` injects it, in which case the `use`
 * resolves too.
 */
class CallableDeclarationResolutionTest : PlatformTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/psi/callable_declaration"

    fun testReferencesInADefResolveToTheirDeclarations() {
        myFixture.configureByFiles("in_def.ex", "eex.ex", "mix_generator.ex")

        assertEquals(
            """
            clause(1) -> def clause(a), do: a
            delegated(1, 2) -> defdelegate delegated(a, b), to: Unresolvable
            exception(message: "x") -> defexception [:message]
            message(%{}) -> defexception [:message]
            from_string(1) -> EEx.function_from_string(:def, :from_string, "<%= a %>", [:a])
            log_template(a: 1) -> Mix.Generator.embed_template(:log, "Log")
            error_text() -> Mix.Generator.embed_text(:error, "Error")
            """.trimIndent(),
            resolutions("clause(1)", "delegated(1, 2)", "exception(message: \"x\")", "message(%{})",
                "from_string(1)", "log_template(a: 1)", "error_text()")
        )
    }

    fun testReferencesInADefResolveToDeclarationsAUseInjects() {
        myFixture.configureByFiles("through_use.ex", "eex.ex", "mix_generator.ex")

        assertEquals(
            """
            clause(1) -> def clause(a), do: a | use Using
            delegated(1, 2) -> defdelegate delegated(a, b), to: Unresolvable | use Using
            exception(message: "x") -> defexception [:message] | use Using
            message(%{}) -> defexception [:message] | use Using
            from_string(1) -> EEx.function_from_string(:def, :from_string, "<%= a %>", [:a]) | use Using
            log_template(a: 1) -> Mix.Generator.embed_template(:log, "Log") | use Using
            error_text() -> Mix.Generator.embed_text(:error, "Error") | use Using
            """.trimIndent(),
            resolutions("clause(1)", "delegated(1, 2)", "exception(message: \"x\")", "message(%{})",
                "from_string(1)", "log_template(a: 1)", "error_text()")
        )
    }

    /**
     * `:"size"` is the same atom as `:size`, so a quoted name declares, and a quoted `as:` targets, what the bare atom
     * would. An interpolated `as:` names nothing fixed, so the delegate resolves to its own head and not to the head's
     * name in the target.
     */
    fun testQuotedAtomsNameWhatTheBareAtomWould() {
        myFixture.configureByFiles("quoted_names.ex", "eex.ex", "mix_generator.ex")

        assertEquals(
            """
            quoted_from_string(1) -> EEx.function_from_string(:def, :"quoted_from_string", "<%= a %>", [:a])
            quoted_text() -> Mix.Generator.embed_text(:"quoted", "Quoted")
            count(1) -> defdelegate count(x), to: QuotedNamesTarget, as: :"size" | def size(x), do: x
            dynamic_count(1) -> defdelegate dynamic_count(x), to: QuotedNamesTarget, as: :"#{:size}"
            """.trimIndent(),
            resolutions("quoted_from_string(1)", "quoted_text()", "count(1)", "dynamic_count(1)")
        )
    }

    /**
     * `embed_template` defines `log_template/1` only: the matching arity resolves, so a regression that stopped
     * recognising the form entirely - not just its arity - would also turn this row into nothing.
     */
    fun testEmbedTemplateDeclaresArityOneOnly() {
        myFixture.configureByText(
            "embed_template_arity.ex",
            """
            defmodule EmbedTemplateArity do
              require Mix.Generator

              Mix.Generator.embed_template(:log, "Log")

              def usage, do: {log_template(a: 1), log_template()}
            end
            """.trimIndent()
        )
        myFixture.copyFileToProject("mix_generator.ex")

        assertEquals(
            """
            log_template(a: 1) -> Mix.Generator.embed_template(:log, "Log")
            log_template() -> nothing
            """.trimIndent(),
            resolutions("log_template(a: 1)", "log_template()")
        )
    }

    /**
     * `Qualifier.unquote(variable)(...)` cannot know the name it will call, so real source reaches this
     * through [org.elixir_lang.reference.resolver.Callable.resolveQualified], which resolves that shape
     * with `name = null`. Calling [org.elixir_lang.psi.scope.call_definition_clause.MultiResolve.resolveResults]
     * directly with `name = null` isolates that one path - before this issue it answered nothing for EEx or
     * generator forms, unlike the other four.
     */
    fun testNamelessQueryOffersEExAndGeneratorNames() {
        myFixture.configureByFiles("nameless_query.ex", "eex.ex", "mix_generator.ex")

        val module = myFixture.file.children.filterIsInstance<Call>().single()
        val names = org.elixir_lang.psi.scope.call_definition_clause.MultiResolve
            .resolveResults(null, 0, false, module)
            .mapNotNull { it.element.text.lineSequence().first() }
            .toSet()

        assertTrue(
            "expected the EEx declaration among the nameless query's candidates, got: $names",
            names.any { it.contains("from_string") }
        )
        assertTrue(
            "expected the generator declaration among the nameless query's candidates, got: $names",
            names.any { it.contains("embed_text") }
        )
    }

    private fun resolutions(vararg usages: String): String {
        val text = myFixture.file.text
        val body = text.indexOf("def usage")

        return usages.joinToString("\n") { usage ->
            val offset = text.indexOf(usage, body)
            assertTrue("`$usage` not found in the usage body", offset >= 0)
            val leaf = myFixture.file.findElementAt(offset)!!
            val reference = generateSequence(leaf) { it.parent }.mapNotNull { it.reference }.first()
            val targets = (reference as PsiPolyVariantReference)
                .multiResolve(false)
                .filter { it.isValidResult }
                .mapNotNull { it.element?.text?.trim() }

            "$usage -> ${targets.joinToString(" | ").ifEmpty { "nothing" }}"
        }
    }
}
