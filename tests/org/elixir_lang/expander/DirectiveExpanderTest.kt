package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageLevel

/**
 * `alias`, `require`, `import` and `__aliases__` over lowered snippets at every supported minor, from the empty env,
 * loading [DirectiveFixtures]' modules. Each expected env is what the plain-Elixir oracle printed for `__CALLER__`
 * after the snippet's last statement on that leg, with the same fixtures.
 */
class DirectiveExpanderTest : ExpanderTestCase() {
    override val exports: Exports = DirectiveFixtures.EXPORTS

    override fun render(code: String, expansion: Expansion): String =
        if (expansion is Expansion.Expanded) DirectiveFixtures.inspect(expansion.env) else super.render(code, expansion)

    // __aliases__

    fun testAnAliasLeavesTheEnvAlone() = assertEvery("Foo", env())

    fun testAnAliasExpandsThroughTheAliases() = assertEvery("alias Foo.Bar\nBar.Baz", env(aliases = "[{Bar, Foo.Bar}]"))

    fun testAnAliasFromTheElixirRoot() = assertEvery("Elixir.Foo", env())

    fun testAnAliasWhoseHeadIsAVariableIsAnError() = assertEvery("x = 1\nx.Foo", "error invalid_alias `x.Foo`")

    fun testAnAliasWhoseHeadIsUnknownIsUnported() = assertEvery("__MODULE__.Foo", "unported `__MODULE__`")

    fun testAnAliasIsAnAtomInABitstring() =
        assertSplit("<<Foo>>", "1.18.0-rc.0", "error invalid_literal `Foo`", env())

    fun testAnAliasIsAnAtomInABitstringPattern() =
        assertLevels("<<Foo>> = <<1>>", LEVELS) { version ->
            when {
                isBefore(version, "1.18.0-rc.0") -> "error invalid_literal `Foo`"
                isBefore(version, "1.19.0-rc.0") -> env()
                else -> "error unknown_match `Foo`"
            }
        }

    fun testRepeatedAliasKeysInAPattern() =
        assertEvery("%{Foo => a, Foo => b} = %{}", "error repeated_key `%{Foo => a, Foo => b}`")

    fun testAliasKeysNamingOneModuleRepeatInAPattern() =
        assertEvery(
            "alias A.X, as: Foo\nalias A.X, as: Bar\n%{Foo => a, Bar => b} = %{}",
            "error repeated_key `%{Foo => a, Bar => b}`",
        )

    fun testAliasKeysNamingTwoModulesDoNotRepeatInAPattern() =
        assertEvery("alias A, as: Foo\n%{Foo => a, Elixir.Foo => b} = %{}", env(aliases = "[{Foo, A}]"))

    // alias

    fun testAlias() = assertEvery("alias Foo.Bar", env(aliases = "[{Bar, Foo.Bar}]"))

    fun testAliasAs() = assertEvery("alias Foo.Bar, as: Baz", env(aliases = "[{Baz, Foo.Bar}]"))

    fun testAliasAsANestedAliasIsAnError() =
        assertEvery("alias Foo.Bar, as: Baz.Qux", "error invalid_alias_for_as `alias Foo.Bar, as: Baz.Qux`")

    fun testAliasAsAnAtomIsAnError() =
        assertEvery("alias Foo.Bar, as: :baz", "error invalid_alias_for_as `alias Foo.Bar, as: :baz`")

    fun testAliasAsTrueIsAnError() =
        assertEvery("alias Foo.Bar, as: true", "error invalid_alias_for_as `alias Foo.Bar, as: true`")

    fun testAliasAsAVariableIsAnError() =
        assertEvery("x = 1\nalias Foo.Bar, as: x", "error invalid_alias_for_as `alias Foo.Bar, as: x`")

    fun testAliasAsNilIsAnErrorFrom1_16() =
        assertSplit(
            "alias Foo.Bar\nalias Foo.Bar, as: nil",
            "1.16.0-rc.0",
            env(aliases = "[{Bar, Foo.Bar}]"),
            "error invalid_alias_for_as `alias Foo.Bar, as: nil`",
        )

    fun testAliasOfAnErlangModuleIsAnErrorFrom1_13() =
        assertSplit(
            "alias :lists",
            "1.13.0-rc.0",
            env(aliases = "[{:\"Elixir.lists\", :lists}]"),
            "error invalid_alias_module `alias :lists`",
        )

    fun testAliasOfNilIsAnErrorFrom1_13() =
        assertSplit(
            "alias nil",
            "1.13.0-rc.0",
            env(aliases = "[{:\"Elixir.nil\", nil}]"),
            "error invalid_alias_module `alias nil`",
        )

    fun testAliasOfTheElixirRootIsAnErrorFrom1_13() =
        assertSplit(
            "alias Elixir",
            "1.13.0-rc.0",
            env(aliases = "[{Elixir.Elixir, Elixir}]"),
            "error invalid_alias_module `alias Elixir`",
        )

    fun testAliasOfAnErlangModuleAs() = assertEvery("alias :lists, as: L", env(aliases = "[{L, :lists}]"))

    fun testAliasOfAnAtom() = assertEvery("alias :\"Elixir.Foo.Bar\"", env(aliases = "[{Bar, Foo.Bar}]"))

    fun testAliasThroughAnAlias() =
        assertEvery("alias Foo.Bar\nalias Bar.Baz", env(aliases = "[{Bar, Foo.Bar}, {Baz, Foo.Bar.Baz}]"))

    fun testAliasOfAModuleToItselfRemovesTheAlias() = assertEvery("alias Foo.Bar\nalias Elixir.Bar, as: Bar", env())

    fun testAliasFromTheElixirRootRemovesTheAlias() = assertEvery("alias Bar.Foo\nalias Elixir.Foo", env())

    fun testAReplacedAliasKeepsItsPlace() =
        assertEvery("alias A.X\nalias B.Y\nalias C.X", env(aliases = "[{X, C.X}, {Y, B.Y}]"))

    fun testTheFirstAsWins() = assertEvery("alias Foo.Bar, as: Baz, as: Qux", env(aliases = "[{Baz, Foo.Bar}]"))

    fun testAsIsNotExpanded() =
        assertEvery("alias Foo.Bar\nalias X.Y, as: Bar", env(aliases = "[{Bar, X.Y}]"))

    fun testAnAliasExpandsOneStepFrom1_16() =
        assertSplit(
            "alias Foo, as: Bar\nalias Baz.Foo\nalias Bar.X",
            "1.16.0-rc.0",
            env(aliases = "[{Bar, Foo}, {Foo, Baz.Foo}, {X, Baz.Foo.X}]"),
            env(aliases = "[{Bar, Foo}, {Foo, Baz.Foo}, {X, Foo.X}]"),
        )

    fun testAPatternSeesTheRightSidesAliasesFrom1_13() =
        assertSplit(
            "%{Bar => a, Foo.Bar => b} = (alias Foo.Bar; %{})",
            "1.13.0-rc.0",
            env(aliases = "[{Bar, Foo.Bar}]"),
            "error repeated_key `%{Bar => a, Foo.Bar => b}`",
        )

    fun testParallelPatternsPairKeysInTheEnvTheySee() =
        assertWindow(
            "%{Foo.Bar => <<x>>} = %{Bar => <<y>>} = (alias Foo.Bar; %{})",
            "1.13.0-rc.0",
            "1.18.0-rc.0",
            env(aliases = "[{Bar, Foo.Bar}]"),
            "error parallel_bitstring_match `<<y>>`",
        )

    fun testAnAliasCycleIsUnportedBefore1_16() =
        assertSplit(
            "alias A, as: B\nalias Elixir.B, as: A\nB",
            "1.16.0-rc.0",
            "unported `B`",
            env(aliases = "[{B, A}, {A, B}]"),
        )

    fun testAliasInAPatternIsAnError() =
        assertEvery("alias(Foo.Bar) = 1", "error invalid_pattern_in_match `alias(Foo.Bar)`")

    fun testAliasInAGuardIsAnError() {
        val code = "alias(Foo.Bar)"

        assertEquals(
            LEVELS.joinToString("\n") { "$it: error invalid_expr_in_guard `alias(Foo.Bar)`" },
            LEVELS.joinToString("\n") { version ->
                val level = ElixirLanguageLevel.of(version)
                val env = Env.empty(level, NO_KERNEL).copy(context = Env.Context.GUARD)

                "$version: " + render(code, Expander.expand(lower(code, level), ExState.empty(level), env, level, exports))
            }
        )
    }

    fun testAliasInABitstringSizeIsAnErrorFrom1_14() =
        assertSplit(
            "<<x::size(alias(Foo.Bar))>> = <<1>>",
            "1.14.0-rc.0",
            "error bad_size_argument `x::size(alias(Foo.Bar))`",
            "error invalid_expr_in_bitsize `alias(Foo.Bar)`",
        )

    fun testAliasOptionsThatAreNotAKeywordListAreAnError() =
        assertEvery("alias Foo, :bar", "error options_are_not_keyword `alias Foo, :bar`")

    fun testAliasTakesOnlyAsAndWarn() =
        assertEvery("alias Foo, only: [a: 1]", "error unsupported_option `alias Foo, only: [a: 1]`")

    fun testAliasWarn() = assertEvery("alias Foo.Bar, warn: false", env(aliases = "[{Bar, Foo.Bar}]"))

    fun testAWarnThatIsNotABooleanIsUnported() =
        assertEvery("alias Foo.Bar, warn: 1", "unported `alias Foo.Bar, warn: 1`")

    fun testAliasOfAVariableIsAnError() =
        assertEvery("x = Foo\nalias x", "error expected_compile_time_module `alias x`")

    fun testAliasOfABinaryIsAnError() =
        assertEvery("alias \"Foo\"", "error expected_compile_time_module `alias \"Foo\"`")

    fun testAliasOfAnAliasWhoseHeadIsAVariableIsAnError() =
        assertEvery("x = 1\nalias x.Foo", "error invalid_alias `x.Foo`")

    // multi-alias

    fun testMultiAlias() = assertEvery("alias Foo.{A, B.C}", env(aliases = "[{A, Foo.A}, {C, Foo.B.C}]"))

    fun testMultiAliasOfAnAliasedBase() =
        assertEvery("alias Foo.Bar\nalias Bar.{A}", env(aliases = "[{Bar, Foo.Bar}, {A, Foo.Bar.A}]"))

    fun testMultiAliasOfANestedAlias() = assertEvery("alias Foo.{Bar.Baz}", env(aliases = "[{Baz, Foo.Bar.Baz}]"))

    fun testMultiAliasOfAnAtom() =
        assertEvery("alias Foo.{:a}", env(aliases = "[{:\"Elixir.a\", :\"Elixir.Foo.a\"}]"))

    fun testMultiAliasAsIsAnError() =
        assertEvery("alias Foo.{A, B}, as: C", "error as_in_multi_alias_call `alias Foo.{A, B}, as: C`")

    fun testMultiAliasOfAnIntegerIsAnError() =
        assertEvery("alias Foo.{1}", "error expected_compile_time_module `alias Foo.{1}`")

    fun testMultiAliasOfAVariableIsAnError() =
        assertEvery("x = 1\nalias Foo.{x}", "error expected_compile_time_module `alias Foo.{x}`")

    fun testMultiAliasOfAVariableBaseIsAnErrorFrom1_19() =
        assertSplit(
            "x = 1\nalias x.{A}",
            "1.19.0-rc.1",
            "unported `alias x.{A}`",
            "error invalid_alias `alias x.{A}`",
        )

    fun testMultiAliasOptionsThatAreNotAListAreUnported() =
        assertEvery("x = []\nalias Foo.{A}, x", "unported `alias Foo.{A}, x`")

    fun testMultiAliasWarn() = assertEvery("alias Foo.{A, B}, warn: false", env(aliases = "[{A, Foo.A}, {B, Foo.B}]"))

    fun testMultiRequire() = assertEvery("require M.{A, B}", env(requires = "[M.A, M.B]"))

    fun testMultiImport() =
        assertEvery("import M.{A, B}", env(requires = "[M.A, M.B]", functions = "[{M.B, [b: 1]}, {M.A, [a: 1]}]"))

    // require

    fun testRequire() = assertEvery("require M", env(requires = "[M]"))

    fun testRequireAs() = assertEvery("require M, as: MM", env(aliases = "[{MM, M}]", requires = "[M]"))

    fun testRequireOfAnAbsentModuleIsAnError() =
        assertEvery("require NoSuchModule", "error unloaded_module `require NoSuchModule`")

    fun testRequireOfNilIsAnError() = assertEvery("require nil", "error unloaded_module `require nil`")

    fun testRequiresAreAnOrdset() = assertEvery("require M.B\nrequire M.A", env(requires = "[M.A, M.B]"))

    fun testRequireOfAnErlangModule() = assertEvery("require :x3e", env(requires = "[:x3e]"))

    fun testRequireOfKernelIsNotLoaded() = assertEvery("require Kernel", env())

    fun testRequireOfAnUnreadableModuleIsUnported() = assertEvery("require U", "unported `require U`")

    fun testRequireRemovesTheAliasNamedAfterTheModuleBefore1_16() =
        assertSplit(
            "alias M.A, as: M\nrequire Elixir.M",
            "1.16.0-rc.0",
            env(requires = "[M]"),
            env(aliases = "[{M, M.A}]", requires = "[M]"),
        )

    fun testRequireAsNilIsAnErrorFrom1_16() =
        assertSplit(
            "require M, as: nil",
            "1.16.0-rc.0",
            env(requires = "[M]"),
            "error invalid_alias_for_as `require M, as: nil`",
        )

    fun testRequireInAPatternIsAnError() =
        assertEvery("require(M) = 1", "error invalid_pattern_in_match `require(M)`")

    fun testRequireOfAVariableIsAnError() =
        assertEvery("x = M\nrequire x", "error expected_compile_time_module `require x`")

    fun testTheModuleBeingDefinedIsCircularFrom1_15WhenItIsLoaded() =
        assertModuleScope(
            "require M",
            module = "Elixir.M",
            contextModules = emptyList(),
        ) { version ->
            if (isBefore(version, "1.15.0-rc.0")) env(requires = "[M]") else "error circular_module `require M`"
        }

    fun testTheModuleBeingDefinedIsCircularWhenItIsNotLoaded() =
        assertModuleScope("require N", module = "Elixir.N", contextModules = listOf("Elixir.N")) {
            "error circular_module `require N`"
        }

    fun testAContextModuleThatIsNotLoadedIsScheduled() =
        assertModuleScope("require N", module = null, contextModules = listOf("Elixir.N")) {
            "error scheduled_module `require N`"
        }

    // import

    fun testImport() = assertEvery("import M", env(requires = "[M]", functions = FUNCTIONS, macros = MACROS))

    fun testImportOnly() =
        assertEvery(
            "import M, only: [f: 1, g: 1, _hidden: 1, mac: 1]",
            env(requires = "[M]", functions = "[{M, [_hidden: 1, f: 1, g: 1]}]", macros = "[{M, [mac: 1]}]"),
        )

    fun testImportOnlyFunctions() = assertEvery("import M, only: :functions", env(requires = "[M]", functions = FUNCTIONS))

    fun testImportOnlyMacros() = assertEvery("import M, only: :macros", env(requires = "[M]", macros = MACROS))

    fun testImportOnlySigilsFrom1_13() =
        assertSplit(
            "import M, only: :sigils",
            "1.13.0-rc.0",
            "error invalid_option `import M, only: :sigils`",
            env(requires = "[M]", functions = "[{M, [sigil_x: 2]}]", macros = "[{M, [sigil_Y: 2]}]"),
        )

    fun testImportOnlySigilsOfAnUnclassifiedSigilNameIsUnportedFrom1_17To1_20() =
        assertLevels("import Sig, only: :sigils", UNCLASSIFIED_SIGIL_LEVELS) {
            when {
                isBefore(it, "1.13.0-rc.0") -> "error invalid_option `import Sig, only: :sigils`"
                isBefore(it, "1.17.0-rc.0") || !isBefore(it, "1.20.0-rc.5") ->
                    env(requires = "[Sig]", functions = "[{Sig, [sigil_x: 2]}]")
                else -> "unported `import Sig, only: :sigils`"
            }
        }

    fun testImportOnlySigilsExceptWithoutAnEarlierImportIsUnportedFrom1_17To1_20() =
        assertLevels("import Sig, only: :sigils, except: []", UNCLASSIFIED_SIGIL_LEVELS) {
            when {
                isBefore(it, "1.13.0-rc.0") -> "error invalid_option `import Sig, only: :sigils, except: []`"
                isBefore(it, "1.17.0-rc.0") || !isBefore(it, "1.20.0-rc.5") ->
                    env(requires = "[Sig]", functions = "[{Sig, [sigil_x: 2]}]")
                else -> "unported `import Sig, only: :sigils, except: []`"
            }
        }

    fun testImportOnlySigilsOverAnEarlierImportIsUnportedFrom1_17To1_20() =
        assertLevels("import Sig, only: [sigil_x: 2]\nimport Sig, only: :sigils", UNCLASSIFIED_SIGIL_LEVELS) {
            when {
                isBefore(it, "1.13.0-rc.0") -> "error invalid_option `import Sig, only: :sigils`"
                isBefore(it, "1.17.0-rc.0") || !isBefore(it, "1.20.0-rc.5") ->
                    env(requires = "[Sig]", functions = "[{Sig, [sigil_x: 2]}]")
                else -> "unported `import Sig, only: :sigils`"
            }
        }

    fun testImportOnlySigilsOfAnUnclassifiedSigilMacroIsUnportedFrom1_17To1_20() =
        assertLevels("import SigMac, only: :sigils", UNCLASSIFIED_SIGIL_LEVELS) {
            when {
                isBefore(it, "1.13.0-rc.0") -> "error invalid_option `import SigMac, only: :sigils`"
                isBefore(it, "1.17.0-rc.0") || !isBefore(it, "1.20.0-rc.5") ->
                    env(requires = "[SigMac]", macros = "[{SigMac, [sigil_Z: 2]}]")
                else -> "unported `import SigMac, only: :sigils`"
            }
        }

    fun testImportOnlySigilsExceptOverAnEarlierImportSkipsTheSigilFilter() =
        assertSplit(
            "import Sig, only: [sigil_x: 2]\nimport Sig, only: :sigils, except: []",
            "1.13.0-rc.0",
            "error invalid_option `import Sig, only: :sigils, except: []`",
            env(requires = "[Sig]", functions = "[{Sig, [sigil_x: 2]}]"),
        )

    fun testImportOnlySigilsExcept() =
        assertSplit(
            "import M, only: :sigils, except: [sigil_x: 2]",
            "1.13.0-rc.0",
            "error invalid_option `import M, only: :sigils, except: [sigil_x: 2]`",
            env(requires = "[M]", macros = "[{M, [sigil_Y: 2]}]"),
        )

    fun testImportExcept() =
        assertEvery(
            "import M, except: [f: 1]",
            env(requires = "[M]", functions = "[{M, [f: 2, g: 1, sigil_x: 2, uses: 1]}]", macros = MACROS),
        )

    fun testImportExceptAnUndefinedName() =
        assertEvery("import M, except: [nope: 1]", env(requires = "[M]", functions = FUNCTIONS, macros = MACROS))

    fun testReimportExceptNarrowsTheEarlierImport() =
        assertEvery(
            "import M, only: [f: 1, g: 1, mac: 1]\nimport M, except: [g: 1]",
            env(requires = "[M]", functions = "[{M, [f: 1]}]", macros = "[{M, [mac: 1]}]"),
        )

    fun testReimportExceptOfAKindTheEarlierImportLeftOutBringsInThatKind() =
        assertEvery(
            "import M, only: [mac: 1]\nimport M, except: [f: 1]",
            env(requires = "[M]", functions = "[{M, [f: 2, g: 1, sigil_x: 2, uses: 1]}]", macros = "[{M, [mac: 1]}]"),
        )

    fun testReimportExceptKeepsWhatTheEarlierImportBroughtIn() =
        assertEvery(
            "import M, only: [g: 1]\nimport M, except: [f: 1]",
            env(requires = "[M]", functions = "[{M, [g: 1]}]", macros = MACROS),
        )

    fun testReimportOnlyMacrosExceptSubtractsFromTheEarlierImport() =
        assertEvery("import M, only: [mac: 1]\nimport M, only: :macros, except: [mac: 1]", env(requires = "[M]"))

    fun testTheNewestImportComesFirst() =
        assertEvery(
            "import M, only: [f: 1]\nimport M.A, only: [a: 1]\nimport M, only: [g: 1]",
            env(requires = "[M, M.A]", functions = "[{M, [g: 1]}, {M.A, [a: 1]}]"),
        )

    fun testAnEmptyImportDropsTheModulesEntry() =
        assertEvery("import M, only: [f: 1]\nimport M, only: []", env(requires = "[M]"))

    fun testOnlyFunctionsDropsTheMacrosEntry() =
        assertEvery("import M\nimport M, only: :functions", env(requires = "[M]", functions = FUNCTIONS))

    fun testOnlyMacrosDropsTheFunctionsEntry() =
        assertEvery("import M\nimport M, only: :macros", env(requires = "[M]", macros = MACROS))

    fun testOnlyFunctionsExcept() =
        assertEvery(
            "import M, only: :functions, except: [f: 1]",
            env(requires = "[M]", functions = "[{M, [f: 2, g: 1, sigil_x: 2, uses: 1]}]"),
        )

    fun testTheFirstOnlyWins() =
        assertEvery("import M, only: [g: 1], only: [f: 1]", env(requires = "[M]", functions = "[{M, [g: 1]}]"))

    fun testTheFirstExceptWins() =
        assertEvery(
            "import M, except: [f: 1], only: :functions, except: :bad",
            env(requires = "[M]", functions = "[{M, [f: 2, g: 1, sigil_x: 2, uses: 1]}]"),
        )

    fun testAnEmptyCharlistIsAnEmptyList() {
        assertEvery("import M, except: ''", env(requires = "[M]", functions = FUNCTIONS, macros = MACROS))
        assertEvery("import M, only: ''", env(requires = "[M]"))
    }

    fun testOnlyNamingAnUndefinedFunctionWarnsFrom1_15() =
        assertSplit(
            "import M, only: [nope: 1]",
            "1.15.0-rc.0",
            "error invalid_import `import M, only: [nope: 1]`",
            env(requires = "[M]"),
        )

    fun testADuplicateInOnlyWarnsFrom1_15() =
        assertSplit(
            "import M, only: [g: 1, g: 1]",
            "1.15.0-rc.0",
            "error duplicated_import `import M, only: [g: 1, g: 1]`",
            env(requires = "[M]", functions = "[{M, [g: 1]}]"),
        )

    fun testADuplicateInExceptWarnsFrom1_15() =
        assertSplit(
            "import M, except: [g: 1, g: 1]",
            "1.15.0-rc.0",
            "error duplicated_import `import M, except: [g: 1, g: 1]`",
            env(requires = "[M]", functions = "[{M, [f: 1, f: 2, sigil_x: 2, uses: 1]}]", macros = MACROS),
        )

    fun testADuplicateInOnlyIsReportedBeforeExceptBefore1_15() =
        assertSplit(
            "import M, only: [g: 1, g: 1], except: [f: 1]",
            "1.15.0-rc.0",
            "error duplicated_import `import M, only: [g: 1, g: 1], except: [f: 1]`",
            "error only_and_except_given `import M, only: [g: 1, g: 1], except: [f: 1]`",
        )

    fun testOnlyAndExceptGiven() =
        assertEvery(
            "import M, only: [f: 1], except: []",
            "error only_and_except_given `import M, only: [f: 1], except: []`",
        )

    fun testExceptIsCheckedBeforeOnlyFrom1_17() =
        assertSplit(
            "import M, only: [g: 1], except: :bad",
            "1.17.0-rc.0",
            "error only_and_except_given `import M, only: [g: 1], except: :bad`",
            "error invalid_option `import M, only: [g: 1], except: :bad`",
        )

    fun testAnInvalidOnlyAndExcept() =
        assertEvery("import M, only: :bad, except: :bad", "error invalid_option `import M, only: :bad, except: :bad`")

    fun testAnOnlyListHoldingANonPairIsAnError() =
        assertEvery("import M, only: [:g, f: 1]", "error invalid_option `import M, only: [:g, f: 1]`")

    fun testAnExceptListHoldingANonPairIsAnError() =
        assertEvery("import M, except: [\"f\"]", "error invalid_option `import M, except: [\"f\"]`")

    fun testAnArityThatIsNotAnIntegerIsAnError() =
        assertEvery("import M, only: [f: \"1\"]", "error invalid_option `import M, only: [f: \"1\"]`")

    fun testOnlyTuplePairs() = assertEvery("import M, only: [{:f, 1}]", env(requires = "[M]", functions = "[{M, [f: 1]}]"))

    fun testExceptAVariableIsAnError() =
        assertEvery("x = [g: 1]\nimport M, except: x", "error invalid_option `import M, except: x`")

    fun testImportOfSpecialFormsDiscardsThemFrom1_17() =
        assertSplit(
            "import S",
            "1.17.0-rc.0",
            "error special_form_conflict `import S`",
            env(requires = "[S]", macros = "[{S, [s: 1]}]"),
        )

    fun testImportOfOnlySpecialFormsKeepsAnEmptyEntryFrom1_17() =
        assertSplit(
            "import S, only: [alias: 2]",
            "1.17.0-rc.0",
            "error special_form_conflict `import S, only: [alias: 2]`",
            env(requires = "[S]", macros = "[{S, []}]"),
        )

    fun testExceptSubtractsFromAnEmptyEntry() =
        assertSplit(
            "import S, only: [alias: 2]\nimport S, except: [s: 1]",
            "1.17.0-rc.0",
            "error special_form_conflict `import S, only: [alias: 2]`",
            env(requires = "[S]"),
        )

    fun testImportOfNoSpecialForm() = assertEvery("import S, only: [s: 1]", env(requires = "[S]", macros = "[{S, [s: 1]}]"))

    fun testImportOfAnErlangModuleLeavesOutBehaviourInfoFrom1_15() =
        assertSplit(
            "import :x3e",
            "1.15.0-rc.0",
            env(requires = "[:x3e]", functions = "[x3e: [behaviour_info: 1, f: 1, g: 2, sigil_x: 2]]"),
            env(requires = "[:x3e]", functions = "[x3e: [f: 1, g: 2, sigil_x: 2]]"),
        )

    fun testOnlyNamingAnErlangModulesInternals() =
        assertSplit(
            "import :x3e, only: [behaviour_info: 1, module_info: 0, f: 1]",
            "1.15.0-rc.0",
            env(requires = "[:x3e]", functions = "[x3e: [behaviour_info: 1, f: 1]]"),
            env(requires = "[:x3e]", functions = "[x3e: [f: 1]]"),
        )

    fun testExceptFromAnErlangModule() =
        assertSplit(
            "import :x3e, except: [f: 1]",
            "1.15.0-rc.0",
            env(requires = "[:x3e]", functions = "[x3e: [behaviour_info: 1, g: 2, sigil_x: 2]]"),
            env(requires = "[:x3e]", functions = "[x3e: [g: 2, sigil_x: 2]]"),
        )

    fun testOnlyMacrosOfAnErlangModuleIsEmptyFrom1_17() =
        assertSplit(
            "import :x3e, only: :macros",
            "1.17.0-rc.0",
            "error no_macros `import :x3e, only: :macros`",
            env(requires = "[:x3e]"),
        )

    fun testOnlySigilsOfAnErlangModuleIsUnported() =
        assertSplit(
            "import :x3e, only: :sigils",
            "1.13.0-rc.0",
            "error invalid_option `import :x3e, only: :sigils`",
            "unported `import :x3e, only: :sigils`",
        )

    fun testTheFirstOnlyOfAnErlangModule() =
        assertEvery("import :x3e, only: [f: 1], only: :macros", env(requires = "[:x3e]", functions = "[x3e: [f: 1]]"))

    fun testImportOfAnAbsentModuleIsAnError() =
        assertEvery("import NoSuchModule", "error unloaded_module `import NoSuchModule`")

    fun testImportTakesOnlyOnlyExceptAndWarn() = assertEvery("import M, as: X", "error unsupported_option `import M, as: X`")

    fun testImportOptionsThatAreNotAListAreAnError() =
        assertEvery("import M, :foo", "error options_are_not_keyword `import M, :foo`")

    fun testImportOptionsThatAreNotPairsAreIgnored() =
        assertEvery("import M, [:foo]", env(requires = "[M]", functions = FUNCTIONS, macros = MACROS))

    fun testImportInAPatternIsAnError() = assertEvery("import(M) = 1", "error invalid_pattern_in_match `import(M)`")

    fun testImportWarn() = assertEvery("import M, warn: false", env(requires = "[M]", functions = FUNCTIONS, macros = MACROS))

    fun testImportRequires() = assertEvery("import M.A", env(requires = "[M.A]", functions = "[{M.A, [a: 1]}]"))

    fun testImportRemovesTheAliasNamedAfterTheModuleBefore1_16() =
        assertSplit(
            "alias M.A, as: M\nimport Elixir.M, only: [g: 1]",
            "1.16.0-rc.0",
            env(requires = "[M]", functions = "[{M, [g: 1]}]"),
            env(aliases = "[{M, M.A}]", requires = "[M]", functions = "[{M, [g: 1]}]"),
        )

    // threading

    fun testAnAliasInsideATupleOutlivesIt() {
        assertEvery("{alias(Foo.Bar), 1}\n:ok", env(aliases = "[{Bar, Foo.Bar}]"))
        assertEvery("{alias(Foo.Bar), 1, 2}\n:ok", env(aliases = "[{Bar, Foo.Bar}]"))
    }

    fun testAnAliasInsideABlockOutlivesIt() = assertEvery("(alias Foo.Bar; :ok)\n:ok", env(aliases = "[{Bar, Foo.Bar}]"))

    fun testAnAliasOnTheRightOfAMatchOutlivesIt() = assertEvery("x = (alias Foo.Bar)\nx", env(aliases = "[{Bar, Foo.Bar}]"))

    fun testAnAliasInsideAListOutlivesIt() = assertEvery("[alias(Foo.Bar)]\n:ok", env(aliases = "[{Bar, Foo.Bar}]"))

    fun testAnAliasInsideAMapValueOutlivesIt() = assertEvery("%{a: alias(Foo.Bar)}\n:ok", env(aliases = "[{Bar, Foo.Bar}]"))

    fun testAnAliasInsideABitstringSegmentOutlivesIt() =
        assertEvery("<<(alias(Foo.Bar); 1)>>\n:ok", env(aliases = "[{Bar, Foo.Bar}]"))

    /** [code] expanded with [module] and [contextModules] set, as `defmodule` sets them. */
    private fun assertModuleScope(
        code: String,
        module: String?,
        contextModules: List<String>,
        expected: (String) -> String,
    ) {
        val versions = (LEVELS + "1.15.0-rc.0").sortedBy { ElixirLanguageLevel.of(it).elixir }

        assertEquals(
            versions.joinToString("\n") { "$it: ${expected(it)}" },
            versions.joinToString("\n") { version ->
                val level = ElixirLanguageLevel.of(version)
                val env = Env.empty(level, NO_KERNEL).copy(module = module, contextModules = contextModules)

                "$version: " + render(code, Expander.expand(lower(code, level), ExState.empty(level), env, level, exports))
            }
        )
    }

    private companion object {
        const val FUNCTIONS = "[{M, [f: 1, f: 2, g: 1, sigil_x: 2, uses: 1]}]"
        const val MACROS = "[{M, [mac: 1, sigil_Y: 2]}]"
        val UNCLASSIFIED_SIGIL_LEVELS = LEVELS + listOf("1.17.0-rc.0", "1.20.0-rc.4", "1.20.0-rc.5")

        fun env(aliases: String = "[]", requires: String = "[]", functions: String = "[]", macros: String = "[]") =
            "[aliases: $aliases, requires: $requires, functions: $functions, macros: $macros]"
    }
}
