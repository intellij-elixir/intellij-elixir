package org.elixir_lang.model.psi

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.lang.Language
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.application.runReadAction
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.NameArity
import org.elixir_lang.documentation.QuickDocumentationTestCase
import org.elixir_lang.model.psi.function.FunctionSymbol
import org.elixir_lang.model.psi.type.TypeSymbol
import org.elixir_lang.model.psi.type.TypeVariableSymbol
import org.elixir_lang.structure_view.element.CallDefinition
import org.elixir_lang.structure_view.element.CallDefinitionSpecification
import org.elixir_lang.structure_view.element.Type
import org.intellij.plugins.markdown.lang.MarkdownLanguage
import org.elixir_lang.structure_view.element.Callback as CallbackElement

/**
 * Elixir quotes `@name (X)` and `@name ((X))` as it quotes `@name X`, so every feature built on an attribute's
 * argument must read X through the parentheses around it.
 *
 * Each case writes X with no, one and two pairs of parentheses, and asserts the same answer for all three, so the
 * unparenthesised spelling is the guard that the case asks the right question.
 */
@Suppress("UnstableApiUsage")
class ParenthesizedModuleAttributeTest : QuickDocumentationTestCase() {
    override fun setUp() {
        super.setUp()
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
    }

    fun testParenthesizedSpecIsASpecification() {
        eachDepth { depth ->
            myFixture.configureByText("spec.ex", module("@spec ${parenthesize("f() :: atom", depth)}", "def f, do: :ok"))

            val nameArities = myFixture.attributes().map { runReadAction { CallDefinitionSpecification.moduleAttributeNameArity(it) } }

            assertEquals("depth $depth", listOf(NameArity("f", 0)), nameArities)

            val function = myFixture.structureViewElements().filterIsInstance<CallDefinition>().single()

            assertTrue(
                "depth $depth: the entry for f/0 holds its spec, children: ${function.children.map { it::class.simpleName }}",
                function.children.any { it is CallDefinitionSpecification }
            )
        }
    }

    fun testParenthesizedTypesAreStructureViewTypes() {
        eachDepth { depth ->
            myFixture.configureByText(
                "types.ex",
                module(
                    "@type ${parenthesize("t :: atom", depth)}",
                    "@typep ${parenthesize("p :: atom", depth)}",
                    "@opaque ${parenthesize("o :: atom", depth)}"
                )
            )

            val names = myFixture.structureViewElements().filterIsInstance<Type>().map { it.presentation.presentableText }

            assertEquals("depth $depth", listOf("t :: atom", "p :: atom", "o"), names)
        }
    }

    fun testParenthesizedCallbacksAreStructureViewCallbacks() {
        eachDepth { depth ->
            myFixture.configureByText(
                "callbacks.ex",
                module(
                    "@callback ${parenthesize("c() :: atom", depth)}",
                    "@macrocallback ${parenthesize("m() :: atom", depth)}"
                )
            )

            val names = myFixture.structureViewElements().filterIsInstance<CallbackElement>().map { it.presentation.presentableText }

            assertEquals("depth $depth", listOf("c/0", "m/0"), names)
        }
    }

    fun testParenthesizedSpecNameResolvesToItsFunction() {
        eachDepth { depth ->
            myFixture.configureByText(
                "spec_name.ex",
                module("@spec ${parenthesize("<caret>f() :: atom", depth)}", "def f, do: :ok")
            )

            assertTrue(
                "depth $depth: the name in a parenthesized @spec should resolve to M.f/0",
                myFixture.resolvedSymbolsAtCaret().filterIsInstance<FunctionSymbol>().any { it.name == "f" && it.moduleName == "M" }
            )
        }
    }

    fun testTypeUseInParenthesizedSpecResolvesToTheType() {
        eachDepth { depth ->
            myFixture.configureByText(
                "type_use.ex",
                module("@type t :: atom", "@spec ${parenthesize("f() :: <caret>t()", depth)}", "def f, do: :ok")
            )

            assertTrue(
                "depth $depth: `t` should resolve to the type",
                myFixture.resolvedSymbolsAtCaret().any { it is TypeSymbol && it.name == "t" }
            )
        }
    }

    fun testTypeUseResolvesToAParenthesizedType() {
        eachDepth { depth ->
            myFixture.configureByText(
                "parenthesized_type.ex",
                module("@type ${parenthesize("t :: atom", depth)}", "@spec f() :: <caret>t()", "def f, do: :ok")
            )

            assertTrue(
                "depth $depth: `t` should resolve to the parenthesized type",
                myFixture.resolvedSymbolsAtCaret().any { it is TypeSymbol && it.name == "t" }
            )
        }
    }

    fun testWhenVariableInParenthesizedSpecResolvesToItsKey() {
        eachDepth { depth ->
            myFixture.configureByText(
                "when_variable.ex",
                module("@spec ${parenthesize("f(x) :: <caret>x when x: atom", depth)}", "def f(_), do: :ok")
            )

            val keys = myFixture.resolvedSymbolsAtCaret().filterIsInstance<TypeVariableSymbol>()

            assertEquals("depth $depth: `x` should resolve to the key of the `when`", listOf("x"), keys.map { it.name })
            assertEquals(
                "depth $depth",
                "x",
                runReadAction { myFixture.file.text.substring(keys.single().range.startOffset, keys.single().range.endOffset) }
            )
        }
    }

    fun testParenthesizedCallbackIsImplementedByADef() {
        eachDepth { depth ->
            myFixture.configureByText(
                "callback.ex",
                "defmodule B do\n  @callback ${parenthesize("perform() :: :ok", depth)}\nend\n\n" +
                    "defmodule Impl do\n  @behaviour B\n\n  def per<caret>form, do: :ok\nend\n"
            )

            assertTrue(
                "depth $depth: a def under `@behaviour B` should implement the parenthesized `@callback`",
                myFixture.resolvedCallbacksAtCaretDef().any { it.name == "perform" && it.moduleName == "B" }
            )
        }
    }

    fun testParenthesizedBehaviourMakesADefImplementTheCallback() {
        eachDepth { depth ->
            myFixture.configureByText(
                "behaviour.ex",
                "defmodule B do\n  @callback perform() :: :ok\nend\n\n" +
                    "defmodule Impl do\n  @behaviour ${parenthesize("B", depth)}\n\n  def per<caret>form, do: :ok\nend\n"
            )

            assertTrue(
                "depth $depth: a def under `@behaviour (B)` should implement B.perform",
                myFixture.resolvedCallbacksAtCaretDef().any { it.name == "perform" && it.moduleName == "B" }
            )
        }
    }

    fun testParenthesizedDocIsQuickDocumentation() {
        eachDepth { depth ->
            myFixture.configureByText(
                "quick_doc.ex",
                module(
                    "@doc ${parenthesize("\"Says hi.\"", depth)}",
                    "@spec ${parenthesize("f() :: atom", depth)}",
                    "def f, do: :ok",
                    "def g, do: <caret>f()"
                )
            )

            val documentation = quickDocumentationAtCaret()

            assertNotNull("depth $depth: Quick Documentation should be shown for a documented call", documentation)
            assertTrue("depth $depth: expected the @doc body in: $documentation", documentation!!.contains("Says hi."))
            assertTrue("depth $depth: expected the spec as `f() :: atom` in: $documentation", documentation.contains("f() :: atom"))
            assertFalse("depth $depth: the spec must not keep its parentheses in: $documentation", documentation.contains("(f() :: atom)"))
        }
    }

    fun testParenthesizedModuledocFalseHidesTheModule() {
        val hidden = "Will make documented invisible to the documentation extraction tools like ExDoc."

        eachDepth { depth ->
            myFixture.configureByText("moduledoc.ex", "defmodule M do\n  @moduledoc ${parenthesize("false", depth)}\nend\n")

            val warnings = myFixture.doHighlighting().filter { it.description == hidden }

            assertEquals("depth $depth: `@moduledoc (false)` gets the weak warning `@moduledoc false` gets", 1, warnings.size)
        }
    }

    fun testParenthesizedAttributesAnnotateAsUnparenthesized() {
        val cases = listOf(
            "@doc" to "\"x\"",
            "@type" to "t(a) :: a",
            "@spec" to "f(a) :: a when a: term()",
            "@callback" to "c(a) :: a when a: term()"
        )

        for ((attribute, argument) in cases) {
            val expected = annotationsInside("$attribute $argument", argument)

            assertFalse("`$attribute $argument` has no annotations to compare", expected.isEmpty())

            eachDepth { depth ->
                assertEquals(
                    "depth $depth: `$attribute ${parenthesize(argument, depth)}`",
                    expected,
                    annotationsInside("$attribute ${parenthesize(argument, depth)}", argument)
                )
            }
        }
    }

    fun testParenthesizedDocHeredocIsMarkdown() {
        eachDepth { depth ->
            myFixture.configureByText(
                "heredoc.ex",
                module("@doc ${parenthesize("\"\"\"\ntext\n\"\"\"", depth)}", "def f, do: :ok")
            )

            assertTrue(
                "depth $depth: the heredoc of `@doc (\"\"\"...\"\"\")` should be a Markdown host",
                MarkdownLanguage.INSTANCE in injectedLanguages()
            )
        }
    }

    private fun eachDepth(block: (depth: Int) -> Unit) {
        for (depth in 0..2) {
            block(depth)
        }
    }

    private fun parenthesize(argument: String, depth: Int): String =
        "(".repeat(depth) + argument + ")".repeat(depth)

    private fun module(vararg lines: String): String =
        "defmodule M do\n" + lines.joinToString("") { line -> "  " + line.replace("\n", "\n  ") + "\n" } + "end\n"

    /** The annotations inside [argument] of [attribute], with ranges relative to the argument. */
    private fun annotationsInside(attribute: String, argument: String): List<String> {
        myFixture.configureByText("annotations.ex", module(attribute, "def f, do: :ok"))

        val start = myFixture.file.text.indexOf(argument)

        return myFixture.doHighlighting()
            .filter { it.startOffset >= start && it.endOffset <= start + argument.length }
            .map { it.relativeTo(start) }
            .sorted()
    }

    private fun HighlightInfo.relativeTo(start: Int): String =
        "${startOffset - start}..${endOffset - start} $severity ${forcedTextAttributesKey?.externalName} " +
            "$forcedTextAttributes $description"

    private fun injectedLanguages(): Set<Language> {
        val file = myFixture.file
        val manager = InjectedLanguageManager.getInstance(project)
        val languages = mutableSetOf<Language>()

        PsiTreeUtil.collectElements(file) { true }.forEach { element ->
            manager.enumerateEx(element, file, true) { injectedPsi, _ -> languages.add(injectedPsi.language) }
        }

        return languages
    }
}
