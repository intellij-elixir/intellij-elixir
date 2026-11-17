package org.elixir_lang.reference.module

import com.intellij.psi.PsiPolyVariantReference
import org.elixir_lang.PlatformTestCase
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
 * | `InFirstArgument`, the alias is the module -> `qualifiableAlias.name` | `alias Prefix.Suffix` | [testSimpleAlias] |
 * | `InFirstArgument`, the module is `Prefix.{Suffix}` -> `fullyQualifiedName()` | `alias Prefix.{Suffix}`, qualifier and leaf | [testMultipleAliasQualifier], [testMultipleAliasLeaf] |
 * | `InAsValue`, the alias is the `as:` value -> `down` of the module | `alias ..., as: X`, `as: (X)`, `alias ((M)), as: X` | [testAsOptionValue], [testParenthesizedAsName], [testParenthesizedAliasedModule] |
 * | `Elsewhere` -> null | the second `as:` | `RepeatedKeywordOptionTest.testUnaliasedNameOfSecondAsIsNotTheModule` |
 * | `null`, no `alias` call around it -> `qualifiableAlias.name` | `defmodule My.Module` | [testNonAliasCallReturnsEntranceName] |
 *
 * ### `down(element: PsiElement)`
 * | Branch | Test |
 * |---|---|
 * | `is QualifiableAlias -> element.name` | [testAsOptionValue] |
 * | `is ElixirAtom -> ":name"` | (atom aliases; not exercised here) |
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

    /**
     * `Prefix.Suffix` in `alias Prefix.Suffix`.
     *
     * The [QualifiedAlias] text ("Prefix.Suffix") is the name returned by the `down` function when it encounters a
     * [org.elixir_lang.psi.QualifiableAlias].
     */
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
    fun testParenthesizedAliasedModule() {
        myFixture.configureByText("parenthesized.ex", "alias ((Foo.Bar)), as: <caret>B\n")

        assertEquals("Foo.Bar", unaliasedName(myFixture.caretAlias()))
    }

    /** Parentheses around the name `as:` gives are read through too: Elixir quotes `(First)` as `First`. */
    fun testParenthesizedAsName() {
        myFixture.configureByText("parenthesized_as.ex", "alias Foo.Bar, as: (<caret>B)\n")

        assertEquals("Foo.Bar", unaliasedName(myFixture.caretAlias()))
    }

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

    /** What [qualifier] of `qualifier.f()` resolves to after [alias]. */
    private fun resolvedAfter(alias: String, qualifier: String = "Bar"): List<String> {
        myFixture.configureByText("resolved_after.ex", "defmodule Foo.Bar do\nend\n\n$alias\n<caret>$qualifier.f()\n")

        val reference = myFixture.file.findReferenceAt(myFixture.caretOffset) as PsiPolyVariantReference

        return reference.multiResolve(false).mapNotNull { it.element?.text }
    }

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
