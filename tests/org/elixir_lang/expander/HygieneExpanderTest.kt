package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Meta
import org.elixir_lang.psi.Import.Term

/**
 * `var!` and `alias!` in a module body, the hygiene counter each expansion takes, and the `counter` metadata that macro
 * output carries. Each expansion renders its variables with their contexts and ends with the module's counter count.
 */
class HygieneExpanderTest : ExpanderTestCase() {
    override val exports: Exports = Exports { module ->
        when (module) {
            KERNEL -> ModuleExports.Present(KERNEL_IMPORTS.functions, KERNEL_IMPORTS.macros, hasInfo = true)
            "Elixir.String" ->
                ModuleExports.Present(listOf(NameArity("length", 1), NameArity("to_atom", 1)), emptyList(), hasInfo = true)
            else -> CallFixtures.EXPORTS.of(module)
        }
    }
    override val kernel: KernelImports = KERNEL_IMPORTS
    override val module: String = CASE

    /** Whether to render the env's aliases instead of the expansion. */
    private var renderingAliases = false

    /** The value of the last top-level expression of the expansion being rendered. */
    private var lastValue: Term? = null

    /** Whether to render the variables read at the last node entered, in place of the expansion. */
    private var renderingInside = false

    /** The variables read at the last node entered. */
    private var inside: Map<Variable, Int> = emptyMap()

    // var!

    fun testVarBangBindsTheVariableInTheCallersContext() =
        assertEvery("var!(x) = 1", "expanded {x/nil:0} next 1; counted 1")

    fun testVarBangInTheMacrosOwnContextTakesTheExpansionsCounter() =
        assertEvery("var!(x, Kernel) = 1", "expanded {x/{Elixir.Case,1}:0} next 1; counted 1")

    fun testEachVarBangInTheMacrosContextIsAVariableOfItsOwn() =
        assertEvery(
            "var!(x, Kernel) = 1\nvar!(x, Kernel) = 2",
            "expanded {x/{Elixir.Case,1}:0 x/{Elixir.Case,2}:1} next 2; counted 2",
        )

    fun testAVariableVarBangBindsInTheMacrosContextIsNotTheCallersVariable() =
        assertSplit(
            "var!(x, Kernel) = 1\ny = x",
            "1.15.0-rc.0",
            "error undefined_function `x`; counted 1",
            "error undefined_var `x`; counted 1",
        )

    fun testVarBangsContextIsAnAliasExpandedThroughTheAliases() =
        assertEvery("alias Bar.Foo\nvar!(v, Foo) = 1", "expanded {v/Elixir.Bar.Foo:0} next 1; counted 1")

    fun testVarBangsContextIsAnAtom() = assertEvery("var!(v, :ctx) = 1", "expanded {v/ctx:0} next 1; counted 1")

    fun testVarBangsContextIsTheModule() =
        assertEvery("var!(v, __MODULE__) = 1", "expanded {v/Elixir.Case:0} next 1; counted 1")

    fun testAVarBangContextTheLoweringLeftAsAPlaceholderIsUnported() {
        val code = "var!(x, c) = 1"

        assertEquals(
            LEVELS.joinToString("\n") { "$it: unported `c`" },
            LEVELS.joinToString("\n") { version ->
                val level = ElixirLanguageLevel.of(version)
                val node = placeholding(lower(code, level), "c")
                val env = Env.empty(level, kernel).copy(module = module)

                "$version: " + render(code, Expander.expand(node, ExState.empty(level), env, level, exports, structs))
            },
        )
    }

    fun testKernelVarBangIsTheRemoteMacro() = assertEvery("Kernel.var!(z) = 3", "expanded {z/nil:0} next 1; counted 1")

    // From 1.20 the `case` takes a version too (`CLAUSES_TAKE_VERSION`).
    fun testVarBangInAGuard() =
        assertSplit(
            "y = 1\ncase 1 do\nw when var!(w) == y -> w\nend",
            "1.20.0-rc.5",
            "expanded {y/nil:0} next 2; counted 1",
            "expanded {y/nil:0} next 3; counted 1",
        )

    fun testTheCounterSurvivesTheClausesOfACase() =
        assertSplit(
            "case 1 do\n1 -> var!(p) = 1\n_ -> var!(q) = 2\nend\nz = 3",
            "1.20.0-rc.5",
            "expanded {z/nil:2} next 3; counted 2",
            "expanded {z/nil:4} next 5; counted 2",
        )

    fun testVarBangOfAMissingVariable() =
        assertSplit(
            "var!(nope)",
            "1.13.0-rc.0",
            "error undefined_var_bang `nope`; counted 1",
            "error undefined_var `nope`; counted 1",
        )

    /** `if_undefined` and `var` are read before the pin. */
    fun testVarBangOfAMissingVariableInAPinIsUndefinedNotUnpinned() =
        assertSplit(
            "^var!(nope) = 1",
            "1.13.0-rc.0",
            "error undefined_var_bang `nope`; counted 1",
            "error undefined_var `nope`; counted 1",
        )

    fun testVarBangDropsTheCounterOfItsVariable() =
        withKeys("x" to listOf(counter(5))) { assertEvery("var!(x) = 1", "expanded {x/nil:0} next 1; counted 1") }

    fun testVarBangOfANonVariable() = assertEvery("var!(1)", "error var_bang_not_a_variable `var!(1)`; counted 0")

    fun testVarBangsContextThatIsAFunctionCallIsNotAnAtom() =
        assertEvery(
            "var!(x, String.to_atom(\"a\"))",
            "error var_bang_context_not_atom `var!(x, String.to_atom(\"a\"))`; counted 0",
        )

    fun testVarBangsContextThatIsAVariableIsNotAnAtom() =
        assertEvery("c = :a\nvar!(x, c) = 1", "error var_bang_context_not_atom `var!(x, c)`; counted 0")

    fun testVarBangsContextThatIsAMacroCallIsOpaque() =
        assertEvery(
            "var!(x, if(true, do: :a)) = 1",
            "opaque imported_macro Elixir.Kernel.if/2 `if(true, do: :a)`; counted 0",
        )

    fun testVarBangsContextThatIsAnUnimportedLocalCallIsNotAnAtom() =
        assertEvery("var!(x, foo()) = 1", "error var_bang_context_not_atom `var!(x, foo())`; counted 0")

    fun testVarBangsContextThatIsACallOnAMissingModuleIsNotAnAtom() =
        assertEvery("var!(x, Nope.f()) = 1", "error var_bang_context_not_atom `var!(x, Nope.f())`; counted 0")

    fun testVarBangsContextThatIsACallOnAVariableIsNotAnAtom() =
        assertEvery("c = :a\nvar!(x, c.f()) = 1", "error var_bang_context_not_atom `var!(x, c.f())`; counted 0")

    /** `Macro.expand/2` leaves an imported function's call as it is, once it has traced it. */
    fun testVarBangsContextThatIsAnImportedFunctionCallIsNotAnAtom() {
        assertEvery("var!(x, inspect(:a)) = 1", "error var_bang_context_not_atom `var!(x, inspect(:a))`; counted 0")
        assertDispatches(
            "var!(x, inspect(:a)) = 1",
            "imported_macro Elixir.Kernel.var!/2, imported_function Elixir.Kernel.inspect/1",
        )
    }

    fun testVarBangsContextThatIsAnEnvFieldIsUnported() =
        assertEvery("var!(x, __ENV__.module) = 1", "unported `__ENV__.module`; counted 0")

    /** `Integer` isn't required: the macro raises, then is unseen, then is a function's call. */
    fun testVarBangsContextThatIsAnUnrequiredRemoteMacroCall() =
        assertLevels("var!(x, Integer.is_odd(1)) = 1", LEVELS) { version ->
            when {
                isBefore(version, "1.12.2") -> "error unrequired_module `Integer.is_odd(1)`; counted 0"
                isBefore(version, "1.13.0-rc.0") -> "unported `Integer.is_odd(1)`; counted 0"
                else -> "error var_bang_context_not_atom `var!(x, Integer.is_odd(1))`; counted 0"
            }
        }

    fun testVarBangsContextThatIsARequiredRemoteMacroCallIsOpaque() =
        assertEvery(
            "require Integer\nvar!(x, Integer.is_odd(1)) = 1",
            "opaque remote_macro Elixir.Integer.is_odd/1 `Integer.is_odd(1)`; counted 0",
        )

    fun testVarBangsContextThatIsAnAliasOfTheModuleIsConcatenated() =
        assertEvery("var!(x, __MODULE__.Foo) = 1", "expanded {x/Elixir.Case.Foo:0} next 1; counted 1")

    fun testVarBangIsDispatchedAndItsOutputDispatchesNothing() {
        assertDispatches("var!(x) = 1", "imported_macro Elixir.Kernel.var!/1")
        assertDispatches("var!(x, Kernel) = 1", "imported_macro Elixir.Kernel.var!/2")
        assertDispatches("Kernel.var!(z) = 3", "remote_macro Elixir.Kernel.var!/1")
    }

    fun testTheVariableVarBangGivesIsEnteredAtItsSource() {
        val code = "var!(x) = 1"
        val entered = mutableListOf<String>()

        expand(code, "1.20.4", object : ExpansionObserver {
            override fun entering(node: ElixirAst, state: ExState, env: Env) {
                if (isVariable(node)) entered.add(node.meta.origin.substring(code))
            }
        })

        assertEquals(listOf("x"), entered)
    }

    // alias!

    fun testAliasBangOfAnAliasIsTheAliasedModule() =
        assertEvery("alias Bar.Foo\nalias!(Foo)", "value :Elixir.Bar.Foo; counted 1")

    fun testAliasBangDropsTheAliasMarkOfItsAlias() =
        withKeys("Foo" to listOf(entry("alias", atom("Elixir.Baz")))) {
            assertEvery("alias Bar.Foo\nalias!(Foo)", "value :Elixir.Bar.Foo; counted 1")
        }

    fun testAliasBangOfAnAtomIsTheAtom() = assertEvery("alias!(:a)", "value :a; counted 1")

    fun testAliasBangOfAnythingElseHasNoClause() =
        assertEvery("_ = alias!(1)", "error alias_bang_function_clause `alias!(1)`; counted 0")

    fun testAliasBangIsDispatchedAndItsOutputDispatchesNothing() =
        assertDispatches("_ = alias!(Foo)", "imported_macro Elixir.Kernel.alias!/1")

    /** A remote capture's function body takes the value of its module part, so `alias!` there expands once. */
    fun testTheModulePartOfARemoteCaptureExpandsOnce() {
        assertLevels("h = &alias!(Integer).to_string(&1, 2)", CAPTURE_LEVELS) {
            // The `fn`'s parameter takes a version, and from 1.20.0-rc.5 the `fn` takes one too.
            val h = if (isBefore(it, "1.20.0-rc.5")) "{h/nil:1} next 2" else "{h/nil:2} next 3"

            "expanded $h; counted ${if (isBefore(it, "1.17.0-rc.1")) 1 else 2}"
        }
        assertSplit(
            "require Integer\nh = &alias!(Integer).is_odd(&1)",
            "1.17.0-rc.1",
            "opaque remote_macro Elixir.Integer.is_odd/1 `alias!(Integer).is_odd(&1)`; counted 1",
            "opaque remote_macro Elixir.Integer.is_odd/1 `alias!(Integer).is_odd(&1)`; counted 2",
        )
    }

    /** From 1.15 a rescue's call is expanded once as `Macro.expand_once/2` does, which takes a counter for a macro. */
    fun testAliasBangInARescueIsExpandedOnce() =
        assertLevels(
            "try do\n1\nrescue\nalias!(ArgumentError) -> 1\nend",
            BOUNDARY_LEVELS.filterNot { isBefore(it, "1.15.0-rc.0") },
        ) { "value Node(kind=OTHER); counted 1" }

    // The definers

    fun testEachDefinitionTakesACounter() =
        assertEvery("def f, do: :ok\ndefp g, do: :ok\nx = 1", "expanded {x/nil:0} next 1; counted 2")

    fun testAnUnquoteFragmentIsLinifiedWithItsDefinitionsCounter() =
        assertAliases(
            "def unquote((alias Foo.Bar, as: B; :f))(), do: :ok",
            "Elixir.B=Elixir.Foo.Bar; macro Elixir.B={Elixir.Case,1}=Elixir.Foo.Bar",
        )

    fun testDefmoduleTakesACounterThatItsDirectiveHolds() =
        assertAliases(
            "defmodule Inner do\nend",
            "Elixir.Inner=Elixir.Case.Inner; macro Elixir.Inner={Elixir.Case,1}=Elixir.Case.Inner",
        )

    fun testARootDefmoduleTakesACounter() =
        assertEvery("defmodule Elixir.Root do\nend\nx = 1", "expanded {x/nil:0} next 1; counted 1")

    fun testADefmoduleNamedByANonAtomTakesACounter() =
        assertEvery("defmodule \"name\" do\nend\nx = 1", "expanded {x/nil:0} next 1; counted 1")

    // A counter outside a module

    fun testOutsideAModuleTheCounterIsAUniqueInteger() {
        val level = ElixirLanguageLevel.of("1.20.4")
        val counters = Counters()
        val expansion = Expander.expand(
            lower("var!(x, Kernel) = 1", level),
            ExState.empty(level),
            Env.empty(level, kernel),
            level,
            exports,
            structs,
            counters = counters,
        ) as Expansion.Expanded

        assertEquals(
            mapOf(Variable("x", Variable.Context.Counter(Env.Counter.Unique(1))) to 0),
            expansion.state.read,
        )
        assertEquals(1L, counters.count(null))
    }

    // if_undefined and var, which only macro output gives a variable

    fun testIfUndefinedRaiseIsHonouredFrom1_13() =
        withKeys("nope" to listOf(entry("if_undefined", atom("raise")))) {
            assertWindow(
                "nope",
                "1.13.0-rc.0",
                null,
                "error undefined_function `nope`; counted 0",
                "error undefined_var `nope`; counted 0",
            )
        }

    fun testIfUndefinedApplyIsALocalCall() =
        withKeys("nope" to listOf(entry("if_undefined", atom("apply")))) {
            assertLevels("nope", BOUNDARY_LEVELS) { "error undefined_function `nope`; counted 0" }
        }

    /** Under `^`, where a missing variable is no local call, `apply` is one only once `if_undefined` is honoured. */
    fun testIfUndefinedApplyUnderAPinIsALocalCallFrom1_13() =
        withKeys("nope" to listOf(entry("if_undefined", atom("apply")))) {
            assertSplit(
                "^nope = 1",
                "1.13.0-rc.0",
                "error undefined_var_pin `nope`; counted 0",
                "error undefined_function `nope`; counted 0",
            )
        }

    fun testWithoutIfUndefinedAMissingVariableRaisesFrom1_15() =
        assertSplit(
            "nope",
            "1.15.0-rc.0",
            "error undefined_function `nope`; counted 0",
            "error undefined_var `nope`; counted 0",
        )

    fun testVarTrueRaisesOnlyBefore1_13() =
        withKeys("nope" to listOf(entry("var", atom("true")))) {
            assertLevels("nope", BOUNDARY_LEVELS) {
                when {
                    isBefore(it, "1.13.0-rc.0") -> "error undefined_var_bang `nope`; counted 0"
                    isBefore(it, "1.15.0-rc.0") -> "error undefined_function `nope`; counted 0"
                    else -> "error undefined_var `nope`; counted 0"
                }
            }
        }

    // The counter metadata of macro output

    fun testACounterLooksUpTheAliasesAsWithoutOne() =
        withKeys("Foo" to listOf(counter(1))) {
            assertEvery("alias Bar.Foo\nFoo", "value :Elixir.Bar.Foo; counted 0")
        }

    fun testAnAliasWithACounterIsAlsoAMacroAlias() =
        withKeys("alias Bar.Foo" to listOf(counter(1))) {
            assertAliases("alias Bar.Foo", "Elixir.Foo=Elixir.Bar.Foo; macro Elixir.Foo={Elixir.Case,1}=Elixir.Bar.Foo")
        }

    fun testAnAliasWithoutACounterIsNoMacroAlias() =
        assertAliases("alias Bar.Foo", "Elixir.Foo=Elixir.Bar.Foo; macro ")

    fun testAnAliasOfAModuleToItselfWithACounterRemovesTheMacroAlias() =
        withKeys("alias Bar.Foo" to listOf(counter(1)), "alias Elixir.Foo" to listOf(counter(2))) {
            assertAliases("alias Bar.Foo\nalias Elixir.Foo", "; macro ")
        }

    fun testAnAliasOfAModuleToItselfWithoutACounterKeepsTheMacroAlias() =
        withKeys("alias Bar.Foo" to listOf(counter(1))) {
            assertAliases("alias Bar.Foo\nalias Elixir.Foo", "; macro Elixir.Foo={Elixir.Case,1}=Elixir.Bar.Foo")
        }

    fun testAliasFalseLooksUpTheMacroAliasOfTheSameCounter() =
        withKeys("alias Bar.Foo" to listOf(counter(1)), "Foo" to listOf(entry("alias", atom("false")), counter(1))) {
            assertEvery("alias Bar.Foo\nFoo", "value :Elixir.Bar.Foo; counted 0")
        }

    fun testAliasFalseMissesTheMacroAliasOfAnotherCounter() =
        withKeys("alias Bar.Foo" to listOf(counter(1)), "Foo" to listOf(entry("alias", atom("false")), counter(2))) {
            assertEvery("alias Bar.Foo\nFoo", "value :Elixir.Foo; counted 0")
        }

    fun testAnAliasMarkedWithItsModuleIsThatModule() =
        withKeys("Foo" to listOf(entry("alias", atom("Elixir.Baz")))) {
            assertEvery("Foo", "value :Elixir.Baz; counted 0")
        }

    /** The aliases lose `Foo` and keep the macro aliases, so only the macro aliases lead `Baz` on to `Bar.Foo`. */
    fun testAMacroAliasChainsBefore1_16() =
        withKeys(
            "alias Bar.Foo" to listOf(counter(1)),
            "alias Elixir.Foo, as: Baz" to listOf(counter(1)),
            "Baz" to listOf(entry("alias", atom("false")), counter(1)),
        ) {
            assertSplit(
                "alias Bar.Foo\nalias Elixir.Foo, as: Baz\nalias Elixir.Foo\nBaz",
                "1.16.0-rc.0",
                "value :Elixir.Bar.Foo; counted 0",
                "value :Elixir.Foo; counted 0",
            )
        }

    // Capture arguments

    fun testEachDistinctCaptureArgumentTakesACounterFrom1_17_0_rc_1() =
        assertInside("&(&1 + &2 + &1)", CAPTURE_LEVELS) {
            when {
                isBefore(it, "1.17.0-rc.1") -> "inside {&1/nil:0 &2/nil:1}; counted 0"
                isBefore(it, "1.20.0-rc.5") ->
                    "inside {capture/{Elixir.Case,1}:0 capture/{Elixir.Case,2}:1}; counted 2"
                else -> "inside {_&/{Elixir.Case,1}:0 _&/{Elixir.Case,2}:1}; counted 2"
            }
        }

    fun testACaptureArgumentIsNotTheVariableOfItsName() =
        assertInside("capture = 5\n&(&1 + capture)", CAPTURE_LEVELS) {
            when {
                isBefore(it, "1.17.0-rc.1") -> "inside {&1/nil:1 capture/nil:0}; counted 0"
                isBefore(it, "1.20.0-rc.5") -> "inside {capture/nil:0 capture/{Elixir.Case,1}:1}; counted 1"
                else -> "inside {_&/{Elixir.Case,1}:1 capture/nil:0}; counted 1"
            }
        }

    fun testADispatchedCaptureTakesNoCounter() {
        assertInside("&Integer.to_string(&1)", CAPTURE_LEVELS) { "inside {}; counted 0" }
        assertInside("&abs/1", CAPTURE_LEVELS) { "inside {}; counted 0" }
    }

    fun testTheModulePartOfARemoteCaptureTakesItsCounterOnce() =
        assertInside("&(&1).to_string(&2)", CAPTURE_LEVELS) {
            when {
                isBefore(it, "1.17.0-rc.1") -> "inside {&1/nil:0 &2/nil:1}; counted 0"
                isBefore(it, "1.20.0-rc.5") ->
                    "inside {capture/{Elixir.Case,1}:0 capture/{Elixir.Case,2}:1}; counted 2"
                else -> "inside {_&/{Elixir.Case,1}:0 _&/{Elixir.Case,2}:1}; counted 2"
            }
        }

    /**
     * From 1.19.0-rc.1 a capture argument's variable records its position after its counter, as the `fn`'s parameter
     * and in its body.
     */
    fun testACaptureArgumentRecordsItsPositionFrom1_19_0_rc_1() {
        val versions = listOf("1.18.4", "1.19.0-rc.0", "1.19.0-rc.1", "1.19.5", "1.20.4")

        assertEquals(
            versions.joinToString("\n") {
                val keys = if (isBefore(it, "1.19.0-rc.1")) "counter" else "counter capture:1"

                "$it: $keys, $keys"
            },
            versions.joinToString("\n") { version ->
                val keys = mutableListOf<String>()

                expand("&(&1 + 1)", version, object : ExpansionObserver {
                    override fun entering(node: ElixirAst, state: ExState, env: Env) {
                        if (isVariable(node)) {
                            keys += node.meta.keys.filterIsInstance<Meta.Key.Entry>().joinToString(" ") {
                                when (val value = it.value) {
                                    is Meta.Value.Integer -> "${it.name}:${value.value}"
                                    else -> it.name
                                }
                            }
                        }
                    }
                })

                "$version: " + keys.joinToString()
            }
        )
    }

    fun testOutsideAModuleACaptureArgumentsCounterIsAUniqueInteger() {
        val level = ElixirLanguageLevel.of("1.20.4")
        val counters = Counters()
        var inside: Map<Variable, Int> = emptyMap()

        Expander.expand(
            lower("&(&1 + 1)", level),
            ExState.empty(level),
            Env.empty(level, kernel),
            level,
            exports,
            structs,
            object : ExpansionObserver {
                override fun entering(node: ElixirAst, state: ExState, env: Env) {
                    inside = state.read
                }
            },
            counters,
        )

        assertEquals(mapOf(Variable("_&", Variable.Context.Counter(Env.Counter.Unique(1))) to 0), inside)
        assertEquals(1L, counters.count(null))
    }

    // Rendering

    /** [code] expands at each of [versions] to [expected]'s variables read at the last node it enters. */
    private fun assertInside(code: String, versions: List<String>, expected: (String) -> String) {
        renderingInside = true
        try {
            assertLevels(code, versions, expected)
        } finally {
            renderingInside = false
        }
    }

    /** [code] leaves the env with [expected]'s aliases, then `; macro `, then its macro aliases, at every level. */
    private fun assertAliases(code: String, expected: String) {
        renderingAliases = true
        try {
            assertEvery(code, expected)
        } finally {
            renderingAliases = false
        }
    }

    private fun assertDispatches(code: String, expected: String) =
        assertEquals(
            LEVELS.joinToString("\n") { "$it: $expected" },
            LEVELS.joinToString("\n") { version ->
                val dispatched = mutableListOf<String>()

                expand(code, version, object : ExpansionObserver {
                    override fun entering(node: ElixirAst, state: ExState, env: Env) {}

                    override fun dispatched(node: ElixirAst, dispatch: Dispatch) {
                        dispatched.add(render(dispatch))
                    }
                })

                "$version: " + dispatched.joinToString()
            }
        )

    override fun expandAndRender(code: String, version: String): String {
        val level = ElixirLanguageLevel.of(version)
        val counters = Counters()
        lastValue = null
        inside = emptyMap()
        val node = lower(code, level)
        // A block's value is the block, so the value rendered is its last expression's.
        val last = (node as? ElixirAst.Block)?.expressions?.lastOrNull() ?: node
        val expansion = Expander.expand(
            node,
            ExState.empty(level),
            Env.empty(level, kernel).copy(module = module),
            level,
            exports,
            structs,
            object : ExpansionObserver {
                override fun entering(node: ElixirAst, state: ExState, env: Env) {
                    inside = state.read
                }

                override fun left(node: ElixirAst, expansion: Expansion) {
                    if (node === last && expansion is Expansion.Expanded) lastValue = expansion.value
                }
            },
            counters,
        )

        if (renderingAliases) {
            val env = (expansion as Expansion.Expanded).env

            return env.aliases.joinToString(" ") { "${it.alias}=${it.module}" } + "; macro " +
                env.macroAliases.joinToString(" ") { "${it.alias}=${context(it.counter)}=${it.module}" }
        }

        val rendered = if (renderingInside) "inside {${render(inside)}}" else render(code, expansion)

        return "$rendered; counted ${counters.count(module)}"
    }

    /** As [ExpanderTestCase.render], with each variable's context, and the value when no variable is read. */
    override fun render(code: String, expansion: Expansion): String =
        when {
            expansion is Expansion.Expanded && expansion.state.read.isEmpty() ->
                "value " + when (val value = lastValue) {
                    is Term.Atom -> ":${value.name}"
                    else -> value.toString()
                }
            expansion is Expansion.Expanded -> {
                val state = expansion.state

                "expanded {${render(state.read)}} next ${state.version}"
            }
            else -> super.render(code, expansion)
        }

    private fun render(read: Map<Variable, Int>): String =
        read.entries
            .sortedWith(compareBy(VARIABLE_ORDER) { it.key })
            .joinToString(" ") { (variable, version) -> "${variable.name}/${context(variable.context)}:$version" }

    private fun context(context: Variable.Context): String =
        when (context) {
            is Variable.Context.Atom -> context.text
            is Variable.Context.Counter -> context(context.counter)
        }

    private fun context(counter: Env.Counter): String =
        when (counter) {
            is Env.Counter.InModule -> "{${counter.module},${counter.n}}"
            is Env.Counter.Unique -> "${counter.n}"
        }

    private companion object {
        const val CASE = "Elixir.Case"
        const val KERNEL = "Elixir.Kernel"

        val KERNEL_IMPORTS = KernelImports(
            functions = CallFixtures.KERNEL.functions,
            macros = CallFixtures.KERNEL.macros + listOf(
                NameArity("var!", 1),
                NameArity("var!", 2),
                NameArity("alias!", 1),
                NameArity("def", 2),
                NameArity("defp", 2),
                NameArity("defmodule", 2),
            ),
        )

        /** [LEVELS] and the first tags either side of the capture counter, and of the capture argument's name. */
        val CAPTURE_LEVELS = (LEVELS + listOf("1.17.0-rc.0", "1.17.0-rc.1", "1.20.0-rc.5"))
            .sortedBy { ElixirLanguageLevel.of(it).elixir }

        /** [LEVELS] and the first tags of the `if_undefined` and undefined-variable changes. */
        val BOUNDARY_LEVELS = (LEVELS + listOf("1.13.0-rc.0", "1.15.0-rc.0")).sortedBy { ElixirLanguageLevel.of(it).elixir }
    }
}
