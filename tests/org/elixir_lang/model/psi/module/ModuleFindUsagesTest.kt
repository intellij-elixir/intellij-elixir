package org.elixir_lang.model.psi.module

import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.assertNoNavigationAtCaret
import org.elixir_lang.code_insight.assertShowUsagesChosenAtCaret
import org.elixir_lang.code_insight.nonDeclarationUsageCountAtCaret
import org.elixir_lang.language_level.atLanguageLevel
import org.elixir_lang.psi.underAnAliasOfNsFoo

/**
 * Behavior-level tests for module Find/Show Usages.
 *
 * Each test describes what the user experiences: which caret position routes to Show Usages
 * and how many references appear in the popup for a given source layout.
 *
 * The pipeline is real caret -> [com.intellij.find.usages.impl.searchTargets] -> [com.intellij.find.usages.impl.buildQuery] -> [com.intellij.find.usages.api.PsiUsage]s, identical to
 * what the Find Usages action invokes. No implementation classes (declaration providers,
 * usage mappers, symbol constructors) are called directly.
 */
class ModuleFindUsagesTest : PlatformTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/model/psi/module"

    // ── Ctrl-click routing ──────────────────────────────────────────────────────────────────────

    fun testCtrlClickOnUnqualifiedModuleDeclarationShowsUsages() {
        myFixture.configureByFiles("usages_alias_use_import.ex")
        myFixture.assertShowUsagesChosenAtCaret()
    }

    fun testCtrlClickOnQualifiedModuleLastSegmentShowsUsages() {
        myFixture.configureByFiles("usages_qualified_alias_last_segment.ex")
        myFixture.assertShowUsagesChosenAtCaret()
    }

    fun testCtrlClickOnQualifiedModuleQualifierShowsUsages() {
        myFixture.configureByFiles("usages_qualified_alias_use_import.ex")
        myFixture.assertShowUsagesChosenAtCaret()
    }

    fun testCtrlClickOnDefprotocolNameShowsUsages() {
        myFixture.configureByFiles("usages_defprotocol.ex")
        myFixture.assertShowUsagesChosenAtCaret()
    }

    fun testCtrlClickOnDefmoduleKeywordDoesNothing() {
        myFixture.configureByFiles("usages_defmodule_keyword.ex")
        myFixture.assertNoNavigationAtCaret()
    }

    // ── Find Usages popup content ───────────────────────────────────────────────────────────────

    /** `alias Target`, `use Target`, `import Target` all appear as usages of `defmodule Target`. */
    fun testFindUsagesOnUnqualifiedModuleFindsAliasUseImportSites() {
        assertEquals(3, nonDeclarationUsageCount("usages_alias_use_import.ex"))
    }

    /** Caret on the last segment of a qualified name still finds all reference sites. */
    fun testFindUsagesFromQualifiedModuleLastSegmentFindsReferences() {
        assertEquals(3, nonDeclarationUsageCount("usages_qualified_alias_last_segment.ex"))
    }

    /** Caret on the qualifier segment of a qualified name still finds all reference sites. */
    fun testFindUsagesFromQualifiedModuleQualifierFindsReferences() {
        assertEquals(3, nonDeclarationUsageCount("usages_qualified_alias_use_import.ex"))
    }

    /** `alias MyApp.{Module, AnotherModule}` multi-alias syntax counts as a usage. */
    fun testFindUsagesFindsMultiAliasSite() {
        assertEquals(1, nonDeclarationUsageCount("usages_multi_alias.ex"))
    }

    /** `MyApp.Module` appearing as a value in general code (e.g. a list literal) counts as a usage. */
    fun testFindUsagesFindsGeneralCodeReference() {
        assertEquals(1, nonDeclarationUsageCount("usages_general_reference.ex"))
    }

    /**
     * A bare `Module` reference where `alias MyApp.Module` is in lexical scope counts as a usage
     * of `MyApp.Module`. Both the alias site and the bare code reference appear in Find Usages.
     */
    fun testFindUsagesFindsAliasedShortNameUsageInCode() {
        assertEquals(2, nonDeclarationUsageCount("usages_aliased_short_name.ex"))
    }

    /**
     * `defmodule Declaration do alias Declaration end` - a module aliased to itself - reports the one
     * alias site once, not twice.
     *
     * The alias is both a reference to the module and lexically inside it, so a search that reaches it
     * by both routes can report the same range twice. `Issue354Test`'s sibling in the old
     * `FindUsagesTest` asserted exactly that duplicate: two usages, both at offset 33, both `ALIAS`.
     * That test was deleted when Find Usages moved to the symbol pipeline and nothing replaced it, so
     * the fixtures it used were left orphaned in `testData/org/elixir_lang/find_usages`.
     */
    fun testFindUsagesOnSelfAliasedModuleReportsTheAliasOnce() {
        assertEquals(1, nonDeclarationUsageCount("usages_self_alias_from_declaration.ex"))
    }

    /** Same file, caret on the alias rather than the declaration: still one usage, not two. */
    fun testFindUsagesFromSelfAliasSiteReportsTheAliasOnce() {
        assertEquals(1, nonDeclarationUsageCount("usages_self_alias_from_alias.ex"))
    }

    /** A `defimpl` of a `defprotocol` and a call through it are usages of the protocol. */
    fun testFindUsagesOnDefprotocolFindsImplementationAndCallSites() {
        assertEquals(2, nonDeclarationUsageCount("usages_defprotocol.ex"))
    }

    /** Same file, caret on the `defimpl`'s protocol alias: the same two usages. */
    fun testFindUsagesFromDefimplProtocolAliasFindsImplementationAndCallSites() {
        assertEquals(2, nonDeclarationUsageCount("usages_defprotocol_from_defimpl.ex"))
    }

    /** After `alias NS.Foo`, a written `Elixir.Foo` is the top-level `Foo`: it is not a usage of `NS.Foo`. */
    fun testFindUsagesOnAModuleDoesNotCountARootedNameThatIsAnotherModuleAfterItsAlias() {
        assertEquals(1, nonDeclarationUsageCountFrom("NS.Foo", NS_FOO_AND_A_ROOTED_FOO))
    }

    /** The same source, caret on the top-level `Foo`: the written `Elixir.Foo` is its one usage. */
    fun testFindUsagesOnAModuleCountsARootedNameAfterAnAliasOfAnotherModule() {
        assertEquals(1, nonDeclarationUsageCountFrom("Foo", NS_FOO_AND_A_ROOTED_FOO))
    }

    /** After `alias NS.Foo`, the member of `alias Elixir.Foo.{Bar}` is the top-level `Foo.Bar`: it is not a usage of `NS.Foo.Bar`. */
    fun testFindUsagesOnAModuleDoesNotCountARootedMultipleAliasMemberThatIsAnotherModuleAfterItsAlias() {
        assertEquals(0, nonDeclarationUsageCountFrom("NS.Foo.Bar", A_ROOTED_MEMBER_OF_FOO))
    }

    /** The same source, caret on the top-level `Foo.Bar`: the member of `alias Elixir.Foo.{Bar}` is its one usage. */
    fun testFindUsagesOnAModuleCountsARootedMultipleAliasMemberAfterAnAliasOfAnotherModule() {
        assertEquals(1, nonDeclarationUsageCountFrom("Foo.Bar", A_ROOTED_MEMBER_OF_FOO))
    }

    /** A `defmodule Inner` in `A` is not named by a written `Elixir.Inner` in `A`: that is a top-level `Inner`. */
    fun testFindUsagesOnANestedModuleDoesNotCountARootedNameOfItsLastSegment() {
        assertEquals(
            0,
            nonDeclarationUsageCountOfText(
                """
                defmodule A do
                  defmodule In<caret>ner do
                  end

                  def g, do: Elixir.Inner
                end
                """.trimIndent()
            )
        )
    }

    // ── A bare name, read through the aliases in lexical scope at it ────────────────────────────

    /** `use` aliases nothing it can tell, so a bare `Repo` after `use MyApp.Repo` is not `MyApp.Repo`. */
    fun testFindUsagesOnAModuleDoesNotCountABareNameAfterAUseOfIt() {
        assertEquals(1, usagesIn("use MyApp.Repo\n\ndef f, do: Repo"))
    }

    /** `import` aliases nothing, so a bare `Repo` after `import MyApp.Repo` is not `MyApp.Repo`. */
    fun testFindUsagesOnAModuleDoesNotCountABareNameAfterAnImportOfIt() {
        assertEquals(1, usagesIn("import MyApp.Repo\n\ndef f, do: Repo"))
    }

    /** An alias applies only to what is written after it. */
    fun testFindUsagesOnAModuleDoesNotCountABareNameBeforeItsAlias() {
        assertEquals(1, usagesIn("def f, do: Repo\n\nalias MyApp.Repo"))
    }

    /** `alias MyApp.Repo, as: R` aliases `R`, not `Repo`. */
    fun testFindUsagesOnAModuleDoesNotCountABareNameAfterAnAliasAsAnotherName() {
        assertEquals(1, usagesIn("alias MyApp.Repo, as: R\n\ndef f, do: Repo"))
    }

    /** Before 1.16, `require Elixir.Repo` removes the alias of `Repo`, so a bare `Repo` after it is the top-level `Repo`. */
    fun testFindUsagesOnAModuleDoesNotCountABareNameAfterARequireRemovesItsAliasBefore116() {
        assertEquals(1, atLanguageLevel(project, "1.15.0") { usagesIn(AN_ALIAS_THEN_A_REQUIRE_OF_THE_TOP_LEVEL_REPO) })
    }

    /** A function's head is no call written before its body, so after an `import` the body's aliases are still known. */
    fun testFindUsagesOnAModuleDoesNotCountABareNameInAFunctionBodyAfterARequireRemovesItsAliasBefore116() {
        assertEquals(
            1,
            atLanguageLevel(project, "1.15.0") {
                usagesIn("import Other\nalias MyApp.Repo\nrequire Elixir.Repo\n\ndef f(c) do\n  Repo\nend")
            }
        )
    }

    /** An `if`'s `else` runs instead of its `do`, so an alias in the `do` doesn't reach the `else`. */
    fun testFindUsagesOnAModuleDoesNotCountABareNameInTheElseOfAnIfWhoseDoAliasesIt() {
        assertEquals(1, usagesIn("def f(c) do\n  if c do\n    alias MyApp.Repo\n  else\n    Repo\n  end\nend"))
    }

    /** Each `case` clause is a scope of its own, so an alias in one doesn't reach the next. */
    fun testFindUsagesOnAModuleDoesNotCountABareNameInACaseClauseAfterOneThatAliasesIt() {
        assertEquals(1, usagesIn("def f(c) do\n  case c do\n    1 -> alias MyApp.Repo\n    _ -> Repo\n  end\nend"))
    }

    /** From 1.16, `require Elixir.Repo` stores nothing, so the alias of `Repo` still holds. */
    fun testFindUsagesOnAModuleCountsABareNameAfterARequireOfTheTopLevelModule() {
        assertEquals(2, atLanguageLevel(project, "1.16.0") { usagesIn(AN_ALIAS_THEN_A_REQUIRE_OF_THE_TOP_LEVEL_REPO) })
    }

    /** An `alias` outside any `defmodule` applies to the rest of the file. */
    fun testFindUsagesOnAModuleCountsABareNameAfterATopLevelAlias() {
        assertEquals(
            2,
            nonDeclarationUsageCountOfText(
                """
                defmodule MyApp.Re<caret>po do
                end

                alias MyApp.Repo

                Repo.all()
                """.trimIndent()
            )
        )
    }

    /** An outer module's `alias` applies inside a `defmodule` nested in it. */
    fun testFindUsagesOnAModuleCountsABareNameInANestedModuleAfterTheOuterModulesAlias() {
        assertEquals(2, usagesIn("alias MyApp.Repo\n\ndefmodule Inner do\n  def f, do: Repo\nend"))
    }

    /** An `alias` inside one `def` applies only in that `def`. */
    fun testFindUsagesOnAModuleDoesNotCountABareNameInAnotherDefinitionThanItsAlias() {
        assertEquals(2, usagesIn("def f do\n  alias MyApp.Repo\n  Repo\nend\n\ndef g, do: Repo"))
    }

    /** A `use` before an `alias` may store aliases of its own, but the `alias` after it still applies. */
    fun testFindUsagesOnAModuleCountsABareNameAfterAUseThenItsAlias() {
        assertEquals(
            2,
            usagesIn("use MyAppWeb, :controller\nalias MyApp.Repo\n\ndef index(conn, _params), do: Repo.all(conn)")
        )
    }

    /** An `alias` whose module can't be read leaves the alias of `Repo` before it as it was. */
    fun testFindUsagesOnAModuleCountsABareNameAfterAnAliasOfAnUnreadableModule() {
        assertEquals(2, usagesIn("alias MyApp.Repo\nalias unquote(module).State\n\ndef f, do: Repo"))
    }

    /** `alias __MODULE__.State` aliases `State` to the enclosing module's `State`. */
    fun testFindUsagesOnAModuleCountsABareNameAfterAnAliasThroughModule() {
        assertEquals(
            2,
            nonDeclarationUsageCountOfText(
                """
                defmodule Outer.Sta<caret>te do
                end

                defmodule Outer do
                  alias __MODULE__.State

                  def f, do: State
                end
                """.trimIndent()
            )
        )
    }

    /** A `use` whose `__using__` quotes an `alias` of the module aliases it, so a bare name after it is the module. */
    fun testFindUsagesOnAModuleCountsABareNameAfterAUseThatAliasesIt() {
        assertEquals(2, usagesIn("use Foo\ndef f, do: Thing", "MyApp.Thing", A_USE_ALIASING_THING))
    }

    /** A `use` whose `__using__` aliases the module replaces an `alias` of another module of that name before it. */
    fun testFindUsagesOnAModuleCountsABareNameAfterAUseThatAliasesItOverAnotherAlias() {
        assertEquals(2, usagesIn("alias Other.Thing\nuse Foo\ndef f, do: Thing", "MyApp.Thing", A_USE_ALIASING_THING))
    }

    /** A member of a multi-alias aliases its last segment. */
    fun testFindUsagesOnAModuleCountsABareNameAfterAMultiAlias() {
        assertEquals(2, usagesIn("alias MyApp.{Repo, Other}\n\ndef f, do: Repo"))
    }

    /** ExUnit's `{:module, _, _, _} = defmodule M1 do ... end` aliases `M1` where it is written, as Elixir expands a match. */
    fun testFindUsagesOnAModuleCountsABareNameAfterAMatchOfItsDefmodule() {
        assertEquals(
            1,
            nonDeclarationUsageCountOfText(
                """
                defmodule User do
                  def run do
                    {:module, _, _, _} = defmodule M<caret>1 do
                    end

                    M1
                  end
                end
                """.trimIndent()
            )
        )
    }

    /** A member of a multi-alias names its base, read through the aliases in scope, then the member. */
    fun testFindUsagesOnAModuleCountsAMemberOfAMultiAliasOnAnAliasedBase() {
        assertEquals(2, usagesIn("alias A.Inner\nalias Inner.{Sub}\n\ndef f, do: Sub", "A.Inner.Sub"))
    }

    /** `alias __MODULE__.{Repo}` aliases `Repo` to the module around it's `Repo`, over an earlier `alias MyApp.Repo`. */
    fun testFindUsagesOnAModuleDoesNotCountABareNameAfterAGroupedAliasThroughModuleTakesItOver() {
        assertEquals(1, usagesIn("alias MyApp.Repo\nalias __MODULE__.{Repo}\n\ndef f, do: Repo"))
    }

    /** The member of `alias __MODULE__.{Repo}` is the module around it's `Repo`, whatever `Repo` was aliased to. */
    fun testFindUsagesOnAModuleDoesNotCountAMemberOfAGroupedAliasThroughModule() {
        assertEquals(1, usagesIn("alias MyApp.Repo\nalias __MODULE__.{Repo}"))
    }

    /** The member of `alias __MODULE__.{Repo}`, and a bare `Repo` after it, are usages of the module around it's `Repo`. */
    fun testFindUsagesOnAModuleCountsAGroupedAliasThroughModuleAndTheBareNameAfterIt() {
        assertEquals(2, usagesIn("alias __MODULE__.{Repo}\n\ndef f, do: Repo", "User.Repo"))
    }

    /** Inside a `defimpl`, `__MODULE__` is the implementation's module, not the module around it. */
    fun testFindUsagesOnAModuleDoesNotCountANameAliasedThroughModuleInAnImplementation() {
        assertEquals(
            0,
            usagesIn(
                "defimpl String.Chars, for: Integer do\n  alias __MODULE__.Repo\n\n  def to_string(value), do: Repo.format(value)\nend",
                "User.Repo"
            )
        )
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    /** Find Usages from `defmodule [module]`, with [before] after it, then [body] in a module `User`. */
    private fun usagesIn(body: String, module: String = "MyApp.Repo", before: String = ""): Int =
        nonDeclarationUsageCountFrom(
            module,
            "defmodule $module do\nend\n\n${before}defmodule User do\n${body.prependIndent("  ")}\nend"
        )

    /** Find Usages from the `defmodule` of [module] in [source], the caret on its name's last letter. */
    private fun nonDeclarationUsageCountFrom(module: String, source: String): Int =
        nonDeclarationUsageCountOfText(
            source.replace("defmodule $module do", "defmodule ${module.dropLast(1)}<caret>${module.last()} do")
        )

    private fun nonDeclarationUsageCountOfText(source: String): Int {
        myFixture.configureByText("usages.ex", source)
        return myFixture.nonDeclarationUsageCountAtCaret(project)
    }

    /**
     * Configures [files], resolves the search target at the caret (the same entry point the Find
     * Usages action uses), runs the query, and returns the count of non-declaration usages.
     */
    private fun nonDeclarationUsageCount(vararg files: String): Int {
        myFixture.configureByFiles(*files)
        return myFixture.nonDeclarationUsageCountAtCaret(project)
    }
}

private val NS_FOO_AND_A_ROOTED_FOO = underAnAliasOfNsFoo("\ndef g, do: Elixir.Foo")

private val A_ROOTED_MEMBER_OF_FOO = underAnAliasOfNsFoo("\nalias Elixir.Foo.{Bar}", defines = "Foo.Bar")

/** `alias MyApp.Repo`, then `require Elixir.Repo`, then a bare `Repo`. */
private const val AN_ALIAS_THEN_A_REQUIRE_OF_THE_TOP_LEVEL_REPO =
    "alias MyApp.Repo\nrequire Elixir.Repo\n\ndef f, do: Repo"

/** A module `Foo` whose `__using__` quotes `alias MyApp.Thing`. */
private const val A_USE_ALIASING_THING =
    "defmodule Foo do\n  defmacro __using__(_) do\n    quote do\n      alias MyApp.Thing\n    end\n  end\nend\n\n"
