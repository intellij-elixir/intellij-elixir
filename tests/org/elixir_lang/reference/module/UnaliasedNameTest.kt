package org.elixir_lang.reference.module

import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiPolyVariantReference
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.enclosingCallAtCaret
import org.elixir_lang.psi.QualifiedAlias
import org.elixir_lang.psi.caretAlias
import org.elixir_lang.reference.module.UnaliasedName.unaliasedName

/**
 * Tests for [UnaliasedName.unaliasedName], covering every [org.elixir_lang.psi.Alias.Position] it asks about and
 * [UnaliasedName.down].
 *
 * ## Branch map
 *
 * ### `Alias.position(alias)`
 * | Position | Elixir | Test |
 * |---|---|---|
 * | `InFirstArgument`, the alias is the module -> its full name | `alias Prefix.Suffix`, `alias __MODULE__.Suffix`, `alias Elixir.Prefix.Suffix` | [testSimpleAlias], [testModuleQualifiedAlias], [testModuleQualifiedAliasInAQuoteResolvesToNoModuleOfTheMacros], [testModuleQualifiedAliasAtTheTopLevel], [testElixirPrefixedAlias]; null for more than one module or none: [testModuleQualifiedAliasInADefimplForAList], [testModuleQualifiedAliasInADefimplForAnEmptyList] |
 * | `InFirstArgument`, the module is `Prefix.{Suffix}` -> its full name | `alias Prefix.{Suffix}`, qualifier and leaf | [testMultipleAliasQualifier], [testMultipleAliasLeaf], [testMultipleAliasLeafOfModuleQualifierInANestedModule], [testMultipleAliasLeafOfModuleQualifierAtTheTopLevel], [testMultipleAliasLeafOfElixirPrefixedQualifier] |
 * | `InAsValue`, the alias is the `as:` value -> `down` of the module | `alias ..., as: X`, `as: (X)`, `alias ((M)), as: X` | [testAsOptionValue], [testParenthesizedAsName], [testParenthesizedAliasedModule] |
 * | `Elsewhere` -> null | the second `as:` | `RepeatedKeywordOptionTest.testUnaliasedNameOfSecondAsIsNotTheModule` |
 * | `null`, no `alias` call around it -> `qualifiableAlias.name` | `defmodule My.Module` | [testNonAliasCallReturnsEntranceName] |
 * | a bare `__MODULE__` -> the module it is | `alias __MODULE__` | [testBareModuleAliasInANestedModule], [testBareModuleAliasInAModuleNamedByModule], [testBareModuleAliasInAQuote]; null for more than one module: [testBareModuleAliasInADefimplForAList] |
 *
 * ### `down(element: PsiElement)`
 * | Branch | Test |
 * |---|---|
 * | `is QualifiableAlias` -> its full name | [testAsOptionValue], [testAsOptionValueOfModuleQualifiedAlias], [testAsOptionValueOfModuleQualifiedAliasInANestedModule], [testAsOptionValueOfModuleQualifiedAliasAtTheTopLevel], [testAsOptionValueOfElixirPrefixedAlias] |
 * | `is ElixirAtom -> ":name"` | (atom aliases; not exercised here) |
 * | `__MODULE__` -> the module it is | [testAsOptionValueOfBareModuleInANestedModule], [testAsOptionValueOfBareModuleInAModuleNamedByModule], [testAsOptionValueOfBareModuleInAQuote]; null for more than one module: [testAsOptionValueOfBareModuleInADefimplForAList] |
 * | `is Call` -> `maybeModularNameToModulars` resolution | (macro-generated aliases; not exercised here) |
 * | `else -> null` | [testUnnameableFirstArgumentHasNoUnaliasedName] |
 */
class UnaliasedNameTest : PlatformTestCase() {

    /**
     * `Prefix` in `alias Prefix.{Suffix}`.
     *
     * Before the fix this caused an infinite loop in the climb to the call; after the fix it answers
     * `fullyQualifiedName()` = "Prefix".
     */
    fun testMultipleAliasQualifier() {
        myFixture.configureByFile("multiple_alias_qualifier.ex")
        val prefix = myFixture.caretAlias()
        assertEquals("Prefix", unaliasedName(prefix))
    }

    /**
     * `Suffix` in `alias Prefix.{Suffix}`.
     *
     * `Alias.position` finds `Prefix.{Suffix}` as the first argument, so the answer is `fullyQualifiedName()`.
     * `fullyQualifiedName()` walks UP the PSI tree to prepend the qualifier, so for `Suffix` inside `Prefix.{Suffix}`
     * it returns "Prefix.Suffix" - the fully-qualified module name, not the bare leaf name.
     */
    fun testMultipleAliasLeaf() {
        myFixture.configureByFile("multiple_alias_leaf.ex")
        val suffix = myFixture.caretAlias()
        assertEquals("Prefix.Suffix", unaliasedName(suffix))
    }

    /** `Prefix.Suffix` in `alias Prefix.Suffix`. */
    fun testSimpleAlias() {
        myFixture.configureByFile("simple_alias.ex")
        // caret is inside "Suffix" - one parent up gives ElixirAlias("Suffix"),
        // one more gives the QualifiedAlias spanning the whole "Prefix.Suffix".
        val qualifiedAlias = myFixture.caretAlias().parent
        assertInstanceOf(qualifiedAlias, QualifiedAlias::class.java)
        assertEquals("Prefix.Suffix", unaliasedName(qualifiedAlias as QualifiedAlias))
    }

    /**
     * `As` (the alias name) in `alias Prefix.Suffix, as: As`.
     *
     * `Alias.position` finds the `as:` value inside the `alias` call, which answers `down(Prefix.Suffix)`,
     * "Prefix.Suffix".
     */
    fun testAsOptionValue() {
        myFixture.configureByFile("as_option.ex")
        val asAlias = myFixture.caretAlias()
        assertEquals("Prefix.Suffix", unaliasedName(asAlias))
    }

    /** Parentheses around the aliased module are read through: Elixir quotes `(Target)` as `Target`. */
    fun testParenthesizedAliasedModule() =
        assertEquals("Foo.Bar", caretAliasUnaliasedName("alias ((Foo.Bar)), as: <caret>B\n"))

    /** Parentheses around the name `as:` gives are read through too: Elixir quotes `(First)` as `First`. */
    fun testParenthesizedAsName() = assertEquals("Foo.Bar", caretAliasUnaliasedName("alias Foo.Bar, as: (<caret>B)\n"))

    /** The name `as:` gives in parentheses resolves, as it does without them: Elixir quotes `(B)` as `B`. */
    fun testParenthesizedAsNameResolves() {
        for (call in listOf("alias Foo.Bar, as: B", "alias Foo.Bar, as: (B)", "alias Foo.Bar, as: ((B))")) {
            assertTrue(call, resolvedAfter(call, "B").contains(call))
        }
    }

    /** Resolving the name `as:` gives, with the module parenthesized, reaches the alias call rather than throwing. */
    fun testParenthesizedAliasedModuleResolves() {
        for (call in listOf("alias (Foo.Bar), as: B", "alias ((Foo.Bar)), as: B")) {
            assertTrue(call, resolvedAfter(call, "B").contains(call))
        }
    }

    /** `alias (Foo.Bar)` quotes as `alias Foo.Bar`, so `Bar` resolves after it to the module and the call, as after that. */
    fun testParenthesizedAliasWithoutAsResolves() {
        for (alias in listOf("alias Foo.Bar", "alias (Foo.Bar)", "alias ((Foo.Bar))")) {
            assertEquals(alias, listOf("defmodule Foo.Bar do\nend", alias), resolvedAfter(alias))
        }
    }

    fun testModuleQualifiedAlias() =
        assertEquals(
            "Repro.Socket",
            caretAliasUnaliasedName("defmodule Repro do\n  alias __MODULE__.<caret>Socket\nend\n", qualified = true)
        )

    fun testAsOptionValueOfModuleQualifiedAlias() =
        assertEquals(
            "Repro.Socket",
            caretAliasUnaliasedName("defmodule Repro do\n  alias __MODULE__.Socket, as: <caret>Sock\nend\n")
        )

    /** `__MODULE__` names the module it is in by its full name, as a nested module's is. */
    fun testAsOptionValueOfModuleQualifiedAliasInANestedModule() =
        assertEquals(
            "Outer.Inner.Socket",
            caretAliasUnaliasedName(
                "defmodule Outer do\n  defmodule Inner do\n    alias __MODULE__.Socket, as: <caret>Sock\n  end\nend\n"
            )
        )

    fun testMultipleAliasLeafOfModuleQualifierInANestedModule() =
        assertEquals(
            "Outer.Inner.Socket",
            caretAliasUnaliasedName(
                "defmodule Outer do\n  defmodule Inner do\n    alias __MODULE__.{<caret>Socket}\n  end\nend\n"
            )
        )

    /** In `defmodule :foo`, `__MODULE__.Socket` is the atom `:"Elixir.foo.Socket"`. */
    fun testModuleQualifiedAliasInAnAtomNamedModule() =
        assertEquals(
            ":Elixir.foo.Socket",
            caretAliasUnaliasedName("defmodule :foo do\n  alias __MODULE__.<caret>Socket\nend\n", qualified = true)
        )

    fun testAsOptionValueOfModuleQualifiedAliasInAnAtomNamedModule() =
        assertEquals(
            ":Elixir.foo.Socket",
            caretAliasUnaliasedName("defmodule :foo do\n  alias __MODULE__.Socket, as: <caret>S\nend\n")
        )

    fun testAsOptionValueOfTwiceQualifiedAliasInAnAtomNamedModule() =
        assertEquals(
            ":Elixir.foo.A.B",
            caretAliasUnaliasedName("defmodule :foo do\n  alias __MODULE__.A.B, as: <caret>AB\nend\n")
        )

    fun testMultipleAliasLeafOfModuleQualifierInAnAtomNamedModule() =
        assertEquals(
            ":Elixir.foo.Pool",
            caretAliasUnaliasedName("defmodule :foo do\n  alias __MODULE__.{<caret>Pool}\nend\n")
        )

    fun testBareModuleAliasInAnAtomNamedModule() =
        assertEquals(":foo", caretModuleUnaliasedName("defmodule :foo do\n  alias <caret>__MODULE__\nend\n"))

    /** After `alias __MODULE__.Socket, as: S` in `defmodule :foo`, `S` is `defmodule __MODULE__.Socket` there. */
    fun testAsOptionValueOfModuleQualifiedAliasResolvesInAnAtomNamedModule() {
        myFixture.configureByText(
            "atom_named_module_alias_resolves.ex",
            """
            defmodule :foo do
              defmodule __MODULE__.Socket do
                def f, do: 1
              end

              alias __MODULE__.Socket, as: S

              def g, do: <caret>S.f()
            end
            """.trimIndent()
        )

        val resolved = resolvedAtCaret()

        assertTrue(resolved.toString(), resolved.any { it.startsWith("defmodule __MODULE__.Socket") })
    }

    /** `alias __MODULE__` names the module `__MODULE__` is, by its full name. */
    fun testBareModuleAliasInANestedModule() =
        assertEquals(
            "Outer.Inner",
            caretModuleUnaliasedName("defmodule Outer do\n  defmodule Inner do\n    alias <caret>__MODULE__\n  end\nend\n")
        )

    fun testBareModuleAliasInAModuleNamedByModule() =
        assertEquals(
            "U.Sub",
            caretModuleUnaliasedName("defmodule U do\n  defmodule __MODULE__.Sub do\n    alias <caret>__MODULE__\n  end\nend\n")
        )

    /** In a `quote`, `__MODULE__` is the module the code is injected into, which the `quote` doesn't know. */
    fun testBareModuleAliasInAQuote() =
        assertEquals(
            "?",
            caretModuleUnaliasedName(
                "defmodule U do\n  defmacro __using__(_) do\n    quote do\n      alias <caret>__MODULE__\n    end\n  end\nend\n"
            )
        )

    /** `M` in `alias __MODULE__, as: M` is the module `__MODULE__` is, by its full name. */
    fun testAsOptionValueOfBareModuleInANestedModule() =
        assertEquals(
            "Outer.Inner",
            caretAliasUnaliasedName(
                "defmodule Outer do\n  defmodule Inner do\n    alias __MODULE__, as: <caret>M\n  end\nend\n"
            )
        )

    fun testAsOptionValueOfBareModuleInAModuleNamedByModule() =
        assertEquals(
            "U.Sub",
            caretAliasUnaliasedName(
                "defmodule U do\n  defmodule __MODULE__.Sub do\n    alias __MODULE__, as: <caret>M\n  end\nend\n"
            )
        )

    fun testAsOptionValueOfBareModuleInAQuote() =
        assertEquals(
            "?",
            caretAliasUnaliasedName(
                "defmodule U do\n  defmacro __using__(_) do\n    quote do\n      alias __MODULE__, as: <caret>M\n    end\n  end\nend\n"
            )
        )

    /** In `defimpl P, for: [A, B]`, `__MODULE__` is `P.A`, then `P.B`, so no one module is named. */
    fun testBareModuleAliasInADefimplForAList() =
        assertNull(caretModuleUnaliasedName("defimpl P, for: [A, B] do\n  alias <caret>__MODULE__\nend\n"))

    fun testAsOptionValueOfBareModuleInADefimplForAList() =
        assertNull(caretAliasUnaliasedName("defimpl P, for: [A, B] do\n  alias __MODULE__, as: <caret>M\nend\n"))

    fun testModuleQualifiedAliasInADefimplForAList() =
        assertNull(
            caretAliasUnaliasedName("defimpl P, for: [A, B] do\n  alias __MODULE__.<caret>X\nend\n", qualified = true)
        )

    /** `defimpl P, for: []` defines no module, so `__MODULE__` has none to name, unlike at the top level. */
    fun testModuleQualifiedAliasInADefimplForAnEmptyList() =
        assertNull(
            caretAliasUnaliasedName("defimpl P, for: [] do\n  alias __MODULE__.<caret>X\nend\n", qualified = true)
        )

    /** After `alias __MODULE__` in `defmodule __MODULE__.Sub`, `Sub` is that module. */
    fun testBareModuleAliasResolvesInAModuleNamedByModule() {
        myFixture.configureByText(
            "bare_module_alias_resolves.ex",
            """
            defmodule U do
              defmodule __MODULE__.Sub do
                alias __MODULE__

                def f, do: 1
                def g, do: <caret>Sub.f()
              end
            end
            """.trimIndent()
        )

        val resolved = resolvedAtCaret()

        assertTrue(resolved.toString(), resolved.any { it.startsWith("defmodule __MODULE__.Sub") })
    }

    /** At the top level `__MODULE__` is `nil`, which Elixir drops from an alias. */
    fun testModuleQualifiedAliasAtTheTopLevel() =
        assertEquals(
            "Socket",
            caretAliasUnaliasedName("alias __MODULE__.<caret>Socket\n", qualified = true, fileName = "top_level.exs")
        )

    fun testAsOptionValueOfModuleQualifiedAliasAtTheTopLevel() =
        assertEquals(
            "Socket",
            caretAliasUnaliasedName("alias __MODULE__.Socket, as: <caret>S\n", fileName = "top_level.exs")
        )

    fun testMultipleAliasLeafOfModuleQualifierAtTheTopLevel() =
        assertEquals("Pool", caretAliasUnaliasedName("alias __MODULE__.{<caret>Pool}\n", fileName = "top_level.exs"))

    /** `Elixir.Foo.Bar` quotes to the atom `Foo.Bar` does. */
    fun testElixirPrefixedAlias() =
        assertEquals("Foo.Bar", caretAliasUnaliasedName("alias Elixir.Foo.<caret>Bar\n", qualified = true))

    fun testAsOptionValueOfElixirPrefixedAlias() =
        assertEquals("Foo.Bar", caretAliasUnaliasedName("alias Elixir.Foo.Bar, as: <caret>B\n"))

    fun testMultipleAliasLeafOfElixirPrefixedQualifier() =
        assertEquals("Foo.Bar", caretAliasUnaliasedName("alias Elixir.Foo.{<caret>Bar}\n"))

    /** In a `quote`, `__MODULE__` is the module the code is injected into, which the `quote` doesn't know. */
    fun testModuleQualifiedAliasInAQuoteResolvesToNoModuleOfTheMacros() {
        myFixture.configureByText(
            "module_qualified_in_quote.ex",
            """
            defmodule Outer.Socket do
            end

            defmodule Outer do
              defmacro __using__(_) do
                quote do
                  alias __MODULE__.Socket

                  def f, do: <caret>Socket.f()
                end
              end
            end
            """.trimIndent()
        )

        assertFalse(resolvedAtCaret().any { it.startsWith("defmodule Outer.Socket") })
    }

    /** What [qualifier] of `qualifier.f()` resolves to after [alias]. */
    private fun resolvedAfter(alias: String, qualifier: String = "Bar"): List<String> {
        myFixture.configureByText("resolved_after.ex", "defmodule Foo.Bar do\nend\n\n$alias\n<caret>$qualifier.f()\n")

        return resolvedAtCaret()
    }

    /** [unaliasedName] of the alias at the caret in [text], or of the [QualifiedAlias] around it when [qualified]. */
    private fun caretAliasUnaliasedName(
        text: String,
        qualified: Boolean = false,
        fileName: String = "caret_alias.ex"
    ): String? {
        myFixture.configureByText(fileName, text)

        val alias = myFixture.caretAlias()

        return unaliasedName(if (qualified) alias.parent as QualifiedAlias else alias)
    }

    /** [unaliasedName] of the `__MODULE__` call at the caret in [text]. */
    private fun caretModuleUnaliasedName(text: String): String? {
        myFixture.configureByText("bare_module_alias.ex", text)

        return unaliasedName(myFixture.enclosingCallAtCaret()!! as PsiNamedElement)
    }

    /** The texts of what the reference at the caret resolves to. */
    private fun resolvedAtCaret(): List<String> =
        (myFixture.file.findReferenceAt(myFixture.caretOffset) as PsiPolyVariantReference)
            .multiResolve(false)
            .mapNotNull { it.element?.text }

    /**
     * `My.Module` in `defmodule My.Module do end`.
     *
     * The [QualifiedAlias] reaches a `defmodule` Call that is not `Kernel.alias/2`, so `Alias.position` is
     * `null` and the answer is `qualifiableAlias.name` = "My.Module" ([QualifiedAlias.getName] returns the full text).
     */
    fun testNonAliasCallReturnsEntranceName() {
        myFixture.configureByFile("defmodule_argument.ex")
        val qualifiedAlias = myFixture.caretAlias().parent
        assertInstanceOf(qualifiedAlias, QualifiedAlias::class.java)
        assertEquals("My.Module", unaliasedName(qualifiedAlias as QualifiedAlias))
    }

    /**
     * `As` in `alias "Foo", as: As`. The first argument is a string, which names no module, so there is
     * no unaliased name: `null`, not a placeholder, and not an error.
     */
    fun testUnnameableFirstArgumentHasNoUnaliasedName() {
        myFixture.configureByFile("string_alias.ex")
        val asAlias = myFixture.caretAlias()

        val (unaliased, errors) = captureLoggedErrors { unaliasedName(asAlias) }

        assertEmpty("an alias of something that is not a module is not an error", errors)
        assertNull(unaliased)
    }

    override fun getTestDataPath(): String =
        "testData/org/elixir_lang/reference/module/unaliased_name"
}
