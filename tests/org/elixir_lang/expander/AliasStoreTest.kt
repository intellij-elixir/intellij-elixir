package org.elixir_lang.expander

import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.psi.Import.Term
import org.elixir_lang.psi.call.Call

/** What a call written in a module body does to the aliases of the scope around it, as [aliasStore] reads it. */
class AliasStoreTest : PlatformTestCase() {
    fun testAnAliasStores() {
        assertStore(AliasStore.STORES, "alias Foo.Bar", "alias")
    }

    fun testARequireWithAsStores() {
        assertStore(AliasStore.STORES, "require Foo.Bar, as: Baz", "require")
    }

    /** From 1.16 `elixir_aliases:alias/6` stores nothing for a `require` without `as:`. */
    fun testARequireWithoutAsStoresNone() {
        assertStore(AliasStore.NONE, "require Foo.Bar", "require", ElixirLanguageLevel.of("1.16.0"))
    }

    /** Before 1.16 `expand_alias/5` stores the module under its own name, which removes the alias keyed by it. */
    fun testARequireWithoutAsRemovesBefore116() {
        assertStore(AliasStore.REMOVES, "require Foo.Bar", "require", ElixirLanguageLevel.of("1.15.0"))
    }

    /** Before 1.16 a `require` with `as:` stores the alias it names, as from 1.16. */
    fun testARequireWithAsStoresBefore116() {
        assertStore(AliasStore.STORES, "require Foo.Bar, as: Baz", "require", ElixirLanguageLevel.of("1.15.0"))
    }

    /** From 1.16 the `require` an `import` implies stores nothing, as it has no `as:`. */
    fun testAnImportStoresNone() {
        assertStore(AliasStore.NONE, "import Foo.Bar", "import", ElixirLanguageLevel.of("1.16.0"))
    }

    /** Before 1.16 an `import` goes through `expand_require/4`, so it removes the alias keyed by its module. */
    fun testAnImportRemovesBefore116() {
        assertStore(AliasStore.REMOVES, "import Foo.Bar", "import", ElixirLanguageLevel.of("1.15.0"))
    }

    /** A module rooted at `Elixir.` with one segment is its own last segment, so the alias removes the one keyed by it. */
    fun testAnAliasOfARootedModuleOfItselfRemoves() {
        assertStore(AliasStore.REMOVES, "alias Elixir.Foo", "alias")
    }

    /** Before 1.16 `as: nil` names the module itself. */
    fun testAnAliasOfARootedModuleAsNilRemovesBefore116() {
        assertStore(AliasStore.REMOVES, "alias Elixir.Foo.Bar, as: nil", "alias", ElixirLanguageLevel.of("1.15.0"))
    }

    /** From 1.16 Elixir raises on `as: nil`, which reads as storing, as no alias after it can be told. */
    fun testAnAliasOfARootedModuleAsNilStoresFrom116() {
        assertStore(AliasStore.STORES, "alias Elixir.Foo.Bar, as: nil", "alias", ElixirLanguageLevel.of("1.16.0"))
    }

    fun testAnAliasOfARootedModuleAsItselfRemoves() {
        assertStore(AliasStore.REMOVES, "alias Elixir.Foo, as: Foo", "alias")
    }

    /** Whether `Foo` names the module `Foo` depends on the aliases before it, so the alias reads as storing. */
    fun testAnAliasAsItselfStores() {
        assertStore(AliasStore.STORES, "alias Foo, as: Foo", "alias")
    }

    fun testARequireOfAMultiAliasWithoutAsStoresNone() {
        assertStore(AliasStore.NONE, "require Foo.{Bar, Baz}", "require", ElixirLanguageLevel.of("1.16.0"))
    }

    fun testARequireOfAMultiAliasWithoutAsRemovesBefore116() {
        assertStore(AliasStore.REMOVES, "require Foo.{Bar, Baz}", "require", ElixirLanguageLevel.of("1.15.0"))
    }

    /** A `require` without `as:` stores nothing from 1.16 whatever its module, so one that can't be read stores none. */
    fun testARequireOfAnUnreadableModuleWithoutAsStoresNone() {
        assertStore(AliasStore.NONE, "require unquote(m)", "require", ElixirLanguageLevel.of("1.16.0"))
    }

    fun testARequireOfAnUnreadableModuleWithoutAsRemovesBefore116() {
        assertStore(AliasStore.REMOVES, "require unquote(m)", "require", ElixirLanguageLevel.of("1.15.0"))
    }

    fun testAnImportOfAnUnreadableModuleStoresNone() {
        assertStore(AliasStore.NONE, "import unquote(m)", "import", ElixirLanguageLevel.of("1.16.0"))
    }

    fun testAnImportOfAnUnreadableModuleRemovesBefore116() {
        assertStore(AliasStore.REMOVES, "import unquote(m)", "import", ElixirLanguageLevel.of("1.15.0"))
    }

    /** A multi-alias with no members expands to no `require`, so it stores nothing before 1.16 either. */
    fun testARequireOfAnEmptyMultiAliasStoresNoneBefore116() {
        assertStore(AliasStore.NONE, "require Foo.{}", "require", ElixirLanguageLevel.of("1.15.0"))
    }

    /** Elixir raises on an `import` of a literal that is no module, which reads as storing. */
    fun testAnImportOfALiteralThatIsNoModuleStores() {
        assertStore(AliasStore.STORES, "import 1", "import", ElixirLanguageLevel.of("1.16.0"))
    }

    /** `__MODULE__` names a module at expansion, so a `require` of an alias it heads removes before 1.16. */
    fun testARequireOfAnAliasThroughModuleRemovesBefore116() {
        assertStore(AliasStore.REMOVES, "require __MODULE__.Foo", "require", ElixirLanguageLevel.of("1.15.0"))
    }

    /** Elixir raises on an `import` with `as:`, which reads as storing. */
    fun testAnImportWithAsStores() {
        assertStore(AliasStore.STORES, "import Foo.Bar, as: Baz", "import")
    }

    fun testStoreAliasOfAModuleUnderAnotherNameAliasesIt() {
        assertEquals(
            AliasStored.Stored(BAR_TABLE, true),
            storeAlias(AliasTable.EMPTY, "Elixir.Foo.Bar", true, NO_OPTIONS, null, legLevel()),
        )
    }

    fun testStoreAliasOfAModuleUnderItsOwnNameRemovesItsAlias() {
        assertEquals(
            AliasStored.Stored(AliasTable.EMPTY, false),
            storeAlias(BAR_TABLE, "Elixir.Bar", false, NO_OPTIONS, null, ElixirLanguageLevel.of("1.15.0")),
        )
    }

    /** From 1.16 a call without `as:` that doesn't alias by default leaves the aliases as they are. */
    fun testStoreAliasWithoutAsLeavesTheAliasesUntouched() {
        assertEquals(
            AliasStored.Untouched,
            storeAlias(BAR_TABLE, "Elixir.Bar", false, NO_OPTIONS, null, ElixirLanguageLevel.of("1.16.0")),
        )
    }

    fun testStoreAliasAsABooleanIsAnError() {
        val asTrue = Term.List(listOf(Term.Pair(Term.Atom("as"), Term.Atom("true"))))

        assertEquals(
            AliasStored.Error("invalid_alias_for_as"),
            storeAlias(AliasTable.EMPTY, "Elixir.Foo", false, asTrue, null, legLevel()),
        )
    }

    fun testADefmoduleDefines() {
        assertStore(AliasStore.DEFINES, "defmodule Foo.Bar do\nend", "defmodule")
    }

    fun testADefprotocolDefines() {
        assertStore(AliasStore.DEFINES, "defprotocol Foo.Bar do\nend", "defprotocol")
    }

    /** A `defmodule` without a body isn't `defmodule/2`. */
    fun testADefmoduleWithoutABodyStoresNone() {
        assertStore(AliasStore.NONE, "defmodule Foo.Bar", "defmodule")
    }

    /** `defmodule/2` defines only with a `do`: Elixir has no clause for another second argument. */
    fun testADefmoduleWithoutADoStoresNone() {
        assertStore(AliasStore.NONE, "defmodule Foo.Bar, []", "defmodule")
    }

    /** `defprotocol/2` defines only with a `do`, as `defmodule/2` does. */
    fun testADefprotocolWithoutADoStoresNone() {
        assertStore(AliasStore.NONE, "defprotocol Foo.Bar, []", "defprotocol")
    }

    /** `Kernel.alias` isn't a macro of `Kernel`: `alias` is `Kernel.SpecialForms`'. */
    fun testAQualifiedKernelAliasStoresNone() {
        assertStore(AliasStore.NONE, "Kernel.alias Foo.Bar", "alias")
    }

    fun testAQualifiedKernelRequireWithAsStoresNone() {
        assertStore(AliasStore.NONE, "Kernel.require Foo.Bar, as: Baz", "require")
    }

    /** `Foo.if` is `Foo`'s call, which may be a macro once `Foo` is required. */
    fun testARemoteIfOfARequiredModuleMayStore() {
        assertStore(AliasStore.MAY_STORE, "require Foo\nFoo.if(true)", "if")
    }

    /** `elixir_import` drops a special form from every import, so no `import` brings another `quote`. */
    fun testASpecialFormAfterAnImportStoresNone() {
        assertStore(AliasStore.NONE, "import Foo\nquote do: 1", "quote")
    }

    /** A macro of `Kernel` and one an `import` brings of the same name make an ambiguous call, so `Kernel`'s is called. */
    fun testAKernelMacroAfterAnImportOfAnotherModuleStoresNone() {
        assertStore(AliasStore.NONE, "import Foo\nunless true, do: 1", "unless")
    }

    /** A `use` may import `Kernel` without `if` and import another `if`. */
    fun testAnIfAfterAUseMayStore() {
        assertStore(AliasStore.MAY_STORE, "use Foo\nif true, do: 1", "if")
    }

    fun testADefimplAfterAUseMayStore() {
        assertStore(AliasStore.MAY_STORE, "use Foo\ndefimpl String.Chars, for: Integer do\nend", "defimpl")
    }

    fun testAnIfAfterAnImportOfKernelExceptItMayStore() {
        assertStore(AliasStore.MAY_STORE, "import Kernel, except: [if: 2]\nif true, do: 1", "if")
    }

    /** Once `Kernel` is imported without `defmodule`, `defmodule` may be another module's macro. */
    fun testADefmoduleAfterAnImportOfKernelExceptItMayStore() {
        assertStore(AliasStore.MAY_STORE, "import Kernel, except: [defmodule: 2]\ndefmodule Inner do\nend", "defmodule")
    }

    /** A `use` doesn't shadow `defmodule`: a nested module after `use ExUnit.Case` is `Kernel`'s. */
    fun testADefmoduleAfterAUseDefines() {
        assertStore(AliasStore.DEFINES, "use Foo\ndefmodule Inner do\nend", "defmodule")
    }

    /** An `import` of another module alone doesn't shadow `defmodule`. */
    fun testADefmoduleAfterAnImportDefines() {
        assertStore(AliasStore.DEFINES, "import Foo\ndefmodule Inner do\nend", "defmodule")
    }

    /** Elixir expands a match's operands where it is written, so the `defmodule` on its right stores its alias. */
    fun testAMatchOfADefmoduleMayStore() {
        assertStore(AliasStore.MAY_STORE, "{:module, _, _, _} = defmodule M1 do\nend", "=")
    }

    /** An `if`'s condition is expanded where the `if` is written. */
    fun testAnIfWhoseConditionAliasesMayStore() {
        assertStore(AliasStore.MAY_STORE, "if (alias A.B2) do\n  :ok\nend", "if")
    }

    /** A `case`'s subject is expanded where the `case` is written. */
    fun testACaseWhoseSubjectAliasesMayStore() {
        assertStore(AliasStore.MAY_STORE, "case (alias A.B3) do\n  _ -> :ok\nend", "case")
    }

    /** A function call's arguments are expanded where it is written. */
    fun testACallWhoseArgumentAliasesMayStore() {
        assertStore(AliasStore.MAY_STORE, "elem({alias(A.B5)}, 0)", "elem")
    }

    /** An `if`'s `do` block is a clause of its own, so an `alias` in it stays there. */
    fun testAnIfWhoseBodyAliasesStoresNone() {
        assertStore(AliasStore.NONE, "if true do\n  alias A.B1\nend", "if")
    }

    /** An `if`'s `do:` is its body, as a `do` block is. */
    fun testAnIfWhoseKeywordBodyAliasesStoresNone() {
        assertStore(AliasStore.NONE, "if true, do: alias(A.B1)", "if")
    }

    /** A function's body runs when it is called, so an `alias` in it stays there. */
    fun testACallOfAFunctionWhoseBodyAliasesStoresNone() {
        assertStore(AliasStore.NONE, "Enum.each([1], fn _ -> alias A.B4 end)", "each")
    }

    /** A `quote` builds code and expands none of it. */
    fun testAQuoteOfAnAliasStoresNone() {
        assertStore(AliasStore.NONE, "quote(do: alias(A.B6))", "quote")
    }

    fun testAKernelMacroAfterAnImportOfKernelOnlyMayStore() {
        assertStore(AliasStore.MAY_STORE, "import Kernel, only: [def: 2]\nunless true, do: 1", "unless")
    }

    /** `Kernel` defines `if/2` only, so `if/3` may be an imported macro beside it. */
    fun testAnIfOfAnotherArityAfterAnImportMayStore() {
        assertStore(AliasStore.MAY_STORE, "import Foo\nif(true, 1, 2)", "if")
    }

    /** `Kernel` defines `use/1,2` only, so `use/3` is no `use`. */
    fun testAUseOfAnotherArityStoresNone() {
        assertStore(AliasStore.NONE, "use Foo, :a, :b", "use")
    }

    /** `use/3` is no `use`, so `Kernel`'s `if` is still the one called after it. */
    fun testAnIfAfterAUseOfAnotherArityStoresNone() {
        assertStore(AliasStore.NONE, "use Foo, :a, :b\nif true, do: 1", "if")
    }

    /** `Kernel` defines `defimpl/2,3` only, so `defimpl/1` may be an imported macro. */
    fun testADefimplOfAnotherArityAfterAnImportMayStore() {
        assertStore(AliasStore.MAY_STORE, "import Bar\ndefimpl Foo", "defimpl")
    }

    /** `import/3` is no special form and no `import`, so a local call after it is `Kernel`'s or none. */
    fun testALocalCallAfterAnImportOfAnotherArityStoresNone() {
        assertStore(AliasStore.NONE, "import Foo, [], []\nfoo()", "foo")
    }

    /** `alias/3` is no special form, so it stores no alias: Elixir finds no `alias/3` to call. */
    fun testAnAliasOfAnotherArityStoresNone() {
        assertStore(AliasStore.NONE, "alias Foo, [as: Bar], []", "alias")
    }

    /** `require/3` is no special form, so after an `import` it may be the imported module's macro. */
    fun testARequireOfAnotherArityAfterAnImportMayStore() {
        assertStore(AliasStore.MAY_STORE, "import Foo\nrequire Bar, [as: Baz], []", "require")
    }

    /** `require/3` is no `require`, so it names no module a remote call after it may reach a macro of. */
    fun testARemoteCallAfterARequireOfAnotherArityStoresNone() {
        assertStore(AliasStore.NONE, "require Foo, [], []\nFoo.foo()", "foo")
    }

    /** A call that names no macro of `Kernel.SpecialForms` or `Kernel` may reach an imported one. */
    fun testALocalCallAfterAnImportMayStore() {
        assertStore(AliasStore.MAY_STORE, "import Foo\nfoo()", "foo")
    }

    fun testALocalCallWithNoImportBeforeItStoresNone() {
        assertStore(AliasStore.NONE, "foo()", "foo")
    }

    /** `is_non_struct_map/1` is no `Kernel` macro before 1.17, so after an `import` it may be the imported module's. */
    fun testIsNonStructMapAfterAnImportMayStoreBefore117() {
        assertStore(AliasStore.MAY_STORE, "import Foo\nis_non_struct_map(x)", "is_non_struct_map", ElixirLanguageLevel.of("1.16.0"))
    }

    /** From 1.17 `is_non_struct_map/1` is `Kernel`'s, which an `import` without `only:` or `except:` leaves in place. */
    fun testIsNonStructMapAfterAnImportStoresNoneFrom117() {
        assertStore(AliasStore.NONE, "import Foo\nis_non_struct_map(x)", "is_non_struct_map", ElixirLanguageLevel.of("1.17.0"))
    }

    /** `dbg/1` is no `Kernel` macro before 1.14, so after an `import` it may be the imported module's. */
    fun testDbgAfterAnImportMayStoreBefore114() {
        assertStore(AliasStore.MAY_STORE, "import Foo\ndbg(x)", "dbg", ElixirLanguageLevel.of("1.13.0"))
    }

    /** `else:` with no `do:` beside it is an option, which Elixir expands where the call is written. */
    fun testAnElseOptionWithoutADoThatAliasesMayStore() {
        assertStore(AliasStore.MAY_STORE, "Keyword.keys(else: alias(Other.Mix))", "keys")
    }

    /** An `if`'s `else:` is a clause of its own, so the alias in it stays there. */
    fun testAnElseBesideADoThatAliasesStoresNone() {
        assertStore(AliasStore.NONE, "if true, do: :ok, else: alias(Other.Mix)", "if")
    }

    /** Every macro a call of `Kernel.SpecialForms` or of a default require may reach, at each arity, is classified by [macroStore]. */
    fun testEveryMacroOfTheSpecialFormsAndTheDefaultRequiresIsClassified() {
        val modules = listOf(SPECIAL_FORMS) + Env.defaultRequires(legLevel())
        val unclassified = modules.flatMap { module ->
            val exports = legExports.of(module) as? ModuleExports.Present ?: throw AssertionError("$module: ${legExports.of(module)}")

            exports.macros
                .filter { macroStore(module, it.name, it.arity, legLevel()) == null }
                .map { "$module.${it.name}/${it.arity}" }
        }

        assertEquals(emptyList<String>(), unclassified)
    }

    /** That the last call named [name] in a module body holding [body] is [expected] at [level]. */
    private fun assertStore(expected: AliasStore, body: String, name: String, level: ElixirLanguageLevel = legLevel()) {
        val file = myFixture.configureByText("a.ex", "defmodule M do\n${body.prependIndent("  ")}\nend\n")
        val call = PsiTreeUtil.findChildrenOfType(file, Call::class.java).last { it.functionName() == name }

        assertEquals(expected, aliasStore(call, level))
    }

    private companion object {
        /** The alias `Bar` of `Foo.Bar`. */
        val BAR = Env.Alias("Elixir.Bar", "Elixir.Foo.Bar")

        val BAR_TABLE = AliasTable(listOf(BAR), emptyList())

        val NO_OPTIONS = Term.List(emptyList())
    }
}
