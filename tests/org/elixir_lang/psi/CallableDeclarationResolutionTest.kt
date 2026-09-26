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

    /**
     * `import` brings in every public function and macro a module defines, however it defines them, resolving just as
     * an imported `def` does - but not a private one, which only its module can call, nor a `@callback`, which the
     * implementing module defines. A `Mix.Generator` embed is a `defp`.
     */
    fun testImportBringsInEveryPublicDefinedFormButCallbacks() {
        myFixture.configureByFiles("through_import.ex", "eex.ex", "mix_generator.ex")

        assertEquals(
            """
            plain(1) -> def plain(x), do: x
            secret(1) -> nothing
            greet("x") -> EEx.function_from_string(:def, :greet, "<%= name %>", [:name])
            hidden() -> nothing
            banner_text() -> nothing
            message(%{}) -> defexception [:message]
            hook() -> nothing
            """.trimIndent(),
            resolutions("plain(1)", "secret(1)", "greet(\"x\")", "hidden()", "banner_text()", "message(%{})", "hook()")
        )
    }

    /** Every form that declares a function is described as one, as usage views and dialogs name it. */
    fun testEveryFunctionDeclaringFormIsDescribedAsAFunction() {
        myFixture.configureByFiles("through_import.ex", "eex.ex", "mix_generator.ex")

        val described = listOf("def plain", "EEx.function_from_string(:def", "Mix.Generator.embed_text").map { start ->
            val call = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(myFixture.file, Call::class.java)
                .first { it.text.startsWith(start) }

            "$start -> ${com.intellij.psi.ElementDescriptionUtil.getElementDescription(call, com.intellij.usageView.UsageViewTypeLocation.INSTANCE)}"
        }

        assertEquals(
            listOf("def plain -> function", "EEx.function_from_string(:def -> function", "Mix.Generator.embed_text -> function"),
            described
        )
    }

    /**
     * `import`'s `only:` brings in the names and arities it lists, and no others - not the second function a
     * `defexception` defines, nor the arity a default argument adds.
     */
    fun testImportOnlyBringsInTheListedNamesAndAritiesAlone() {
        myFixture.configureByFiles("import_only.ex")

        assertEquals(
            """
            exception(message: "x") -> defexception [:message]
            message(%{}) -> nothing
            pad(1) -> def pad(a, b \\ 1), do: {a, b}
            pad(1, 2) -> nothing
            """.trimIndent(),
            resolutions("exception(message: \"x\")", "message(%{})", "pad(1)", "pad(1, 2)")
        )
    }

    /** `except:` leaves out the arities it lists, not the rest of a definition's: `f/2` stays when `f/1` is excluded. */
    fun testImportExceptLeavesOutTheListedArityAlone() {
        myFixture.configureByText(
            "import_except.ex",
            """
            defmodule Padding do
              def pad(a, b \\ 1), do: {a, b}
            end

            defmodule User do
              import Padding, except: [pad: 1]

              def usage, do: {pad(1), pad(1, 2)}
            end
            """.trimIndent()
        )

        assertEquals(
            """
            pad(1) -> nothing
            pad(1, 2) -> def pad(a, b \\ 1), do: {a, b}
            """.trimIndent(),
            resolutions("pad(1)", "pad(1, 2)")
        )
    }

    /** `only: :functions`, `:macros` and `:sigils` bring in what each names, as Elixir's `import` does. */
    fun testImportOnlyAKindBringsInThatKind() {
        val kinds = """
            defmodule Kinds do
              def f(a), do: a
              defmacro m(a), do: a
              def sigil_x(string, _), do: string
            end
        """.trimIndent()

        fun importing(selector: String): String {
            myFixture.configureByText(
                "import_$selector.ex",
                "$kinds\n\ndefmodule User do\n  import Kinds, only: :$selector\n\n  def usage, do: {f(1), m(1), sigil_x(\"a\", [])}\nend\n"
            )

            return resolutions("f(1)", "m(1)", "sigil_x(\"a\", [])").lines().joinToString(", ") { it.substringBefore(" -> ") + " " + !it.endsWith("nothing") }
        }

        assertEquals("f(1) true, m(1) false, sigil_x(\"a\", []) true", importing("functions"))
        assertEquals("f(1) false, m(1) true, sigil_x(\"a\", []) false", importing("macros"))
        assertEquals("f(1) false, m(1) false, sigil_x(\"a\", []) true", importing("sigils"))
    }

    /** A kind and `except:` together narrow to the kind, then leave out what `except:` lists, in either order. */
    fun testImportOnlyAKindExceptSomeOfIt() {
        fun importing(options: String): String {
            myFixture.configureByText(
                "import_kind_except.ex",
                """
                defmodule Kinds do
                  def f(a), do: a
                  def g(a), do: a
                  defmacro m(a), do: a
                end

                defmodule User do
                  import Kinds, $options

                  def usage, do: {f(1), g(1), m(1)}
                end
                """.trimIndent()
            )

            return resolutions("f(1)", "g(1)", "m(1)").lines().joinToString(", ") { it.substringBefore(" -> ") + " " + !it.endsWith("nothing") }
        }

        assertEquals("f(1) false, g(1) true, m(1) false", importing("only: :functions, except: [f: 1]"))
        assertEquals("f(1) false, g(1) true, m(1) false", importing("except: [f: 1], only: :functions"))
    }

    /** A name starting with `_` is imported only when `only:` names it. */
    fun testImportLeavesOutUnderscoreNamesUnlessOnlyNamesThem() {
        val hidden = """
            defmodule Hidden do
              def _hidden, do: :ok
              def shown, do: :ok
            end
        """.trimIndent()

        fun importing(options: String): String {
            myFixture.configureByText(
                "import_hidden.ex",
                "$hidden\n\ndefmodule User do\n  import Hidden$options\n\n  def usage, do: {_hidden(), shown()}\nend\n"
            )

            return resolutions("_hidden()", "shown()").lines().joinToString(", ") { it.substringBefore(" -> ") + " " + !it.endsWith("nothing") }
        }

        assertEquals("_hidden() false, shown() true", importing(""))
        assertEquals("_hidden() true, shown() false", importing(", only: [_hidden: 0]"))
    }

    /** An EEx function given `@args` could take any arity, so a call and a `@spec` of any arity both resolve to it. */
    fun testAnUnknownEExArityAcceptsAnyArity() {
        myFixture.configureByFiles("unknown_arity.ex", "eex.ex")

        assertEquals(
            """
            render(1) -> EEx.function_from_string(:def, :render, "<%= a %>", @args)
            render(term) -> EEx.function_from_string(:def, :render, "<%= a %>", @args)
            """.trimIndent(),
            resolutions("render(1)", "render(term)")
        )
    }

    /** A `@spec` names a function this module defines, whichever form defines it. */
    fun testSpecResolvesToTheFormThatDefinesIt() {
        myFixture.configureByFiles("spec_targets.ex", "eex.ex")

        assertEquals(
            """
            greet(term) -> EEx.function_from_string(:def, :greet, "<%= name %>", [:name])
            message(t) -> defexception [:message]
            """.trimIndent(),
            resolutions("greet(term)", "message(t)")
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
     * directly with `name = null` isolates that one path.
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
