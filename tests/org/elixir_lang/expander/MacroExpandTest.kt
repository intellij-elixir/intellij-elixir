package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Meta

/**
 * `Macro.expand/2` of a snippet in a module body: the node it gives, or where the port stops, and the dispatches it
 * traced.
 */
class MacroExpandTest : ExpanderTestCase() {
    override val exports: Exports = Exports { module ->
        when (module) {
            KERNEL -> ModuleExports.Present(kernel.functions, kernel.macros, hasInfo = true)
            LIST -> ModuleExports.Present(listOf(NameArity("first", 1)), emptyList(), hasInfo = true)
            INTEGER -> ModuleExports.Present(emptyList(), listOf(NameArity("is_odd", 1)), hasInfo = true)
            MODULE -> ModuleExports.Present(emptyList(), listOf(NameArity("foo", 1)), hasInfo = true)
            UNREADABLE -> ModuleExports.Unreadable
            else -> ModuleExports.Absent
        }
    }
    override val kernel = KernelImports(
        functions = listOf(NameArity("+", 1), NameArity("-", 1), NameArity("inspect", 1), NameArity("length", 1)),
        macros = listOf(
            NameArity("!", 1), NameArity("alias!", 1), NameArity("def", 2), NameArity("defmodule", 2), NameArity("if", 2),
            NameArity("unless", 2), NameArity("var!", 1), NameArity("var!", 2),
        ),
    )

    /** Render the output node's name, whether its meta has a line, and the counters taken, in place of the node. */
    private var output = false

    private var inModule: String? = MODULE
    override val module: String? get() = inModule

    private var aliases = emptyList<Env.Alias>()
    private var functions = emptyList<Env.Imports>()
    private var macros = emptyList<Env.Imports>()
    private var requires = emptyList<String>()

    // Atoms and aliases

    fun testAnAtomIsUnchanged() = assertEvery(":a", UNCHANGED)

    fun testAnAliasIsItsModule() = assertEvery("Foo.Bar", "atom Elixir.Foo.Bar |")

    fun testAnAliasIsExpandedThroughTheEnvsAliases() {
        aliases = listOf(Env.Alias("Elixir.Foo", "Elixir.Other.Foo"))

        assertEvery("Foo.Bar", "atom Elixir.Other.Foo.Bar |")
    }

    fun testAnAliasWhoseHeadIsModuleIsConcatenated() = assertEvery("__MODULE__.Foo", "atom Elixir.Case.Foo |")

    fun testAnAliasWhoseHeadIsAVariableIsUnchanged() = assertEvery("x.Foo", UNCHANGED)

    // The environment's macros

    fun testModuleIsTheEnvsModule() = assertEvery("__MODULE__", "atom Elixir.Case |")

    fun testModuleIsNilOutsideAModule() {
        inModule = null

        assertEvery("__MODULE__", "atom nil |")
    }

    fun testDirIsTheFilesDirectory() = assertEvery("__DIR__", "dir |")

    fun testEnvIsUnported() = assertEvery("__ENV__", "unported `__ENV__` |")

    fun testAnEnvFieldIsUnported() = assertEvery("__ENV__.module", "unported `__ENV__.module` |")

    /** `__ENV__` expands to a map, which isn't a receiver. */
    fun testACallOnEnvWithArgumentsIsUnchanged() = assertEvery("__ENV__.f(1)", UNCHANGED)

    fun testAVariableIsUnchanged() = assertEvery("x", UNCHANGED)

    // Local calls

    fun testASpecialFormIsUnchanged() = assertEvery("x = 1", UNCHANGED)

    /** No `import` can bring in a special form, but a special form is never looked up among the imports. */
    fun testASpecialFormIsNotLookedUpAmongTheImports() {
        functions = listOf(Env.Imports(OTHER, listOf(NameArity("=", 2))))

        assertEvery("x = 1", UNCHANGED)
    }

    fun testAnImportedFunctionIsUnchangedAndTraced() =
        assertEvery("inspect(1)", "unchanged | imported_function Elixir.Kernel.inspect/1")

    fun testAnImportedFunctionIsTracedInlined() =
        assertEvery("length([1])", "unchanged | imported_function erlang.length/1")

    fun testACallOfNothingImportedIsUnchanged() = assertEvery("foo(1)", UNCHANGED)

    fun testAnAmbiguousImportIsAnError() {
        functions = listOf(Env.Imports(OTHER, listOf(NameArity("inspect", 1))))

        assertEvery("inspect(1)", "error ambiguous_call `inspect(1)` |")
    }

    fun testUnaryMinusOfAnIntegerFolds() = assertEvery("-(1)", "integer -1 | imported_function erlang.-/1")

    fun testUnaryPlusOfAnIntegerFolds() = assertEvery("+(1)", "integer 1 | imported_function erlang.+/1")

    fun testTheFoldExpandsItsArgumentOnce() =
        assertEvery("-(-(2))", "integer 2 | imported_function erlang.-/1, imported_function erlang.-/1")

    fun testTheFoldOfAVariableIsUnchanged() = assertEvery("-(x)", "unchanged | imported_function erlang.-/1")

    fun testTheFoldOfAFloatIsUnchanged() = assertEvery("-(1.0)", "unchanged | imported_function erlang.-/1")

    /** Elixir traces a quoted import's function inside `Macro.expand/2` only from 1.18.4. */
    fun testAQuotedFunctionIsTracedFrom1_18_4() =
        withKeys(FIRST to listOf(CONTEXT, entry("import", atom(LIST)), imports(1, LIST))) {
            assertSplit(FIRST, "1.18.4", UNCHANGED, "unchanged | remote_function Elixir.List.first/1")
        }

    fun testAnImportedMacroWithoutASummaryIsOpaque() {
        macros = listOf(Env.Imports(OTHER, listOf(NameArity("foo", 1))))

        assertEvery("foo(1)", "opaque imported_macro Elixir.Other.foo/1 `foo(1)` |")
    }

    /** A definer's summary isn't a [Summary.Rewrite], so its output can't be built. */
    fun testAnImportedMacroWithAPlainSummaryIsUnported() {
        assertEvery("def f, do: 1", "unported `def f, do: 1` |")
        assertEvery("defmodule M do\nend", "unported `defmodule M do\nend` |")
    }

    fun testAMacroIsItsOutputExpanded() =
        assertEvery("alias!(Foo)", "atom Elixir.Foo | imported_macro Elixir.Kernel.alias!/1")

    fun testAMacroWhoseOutputIsAVariableIsThatVariable() =
        assertEvery("var!(x)", "node `x` | imported_macro Elixir.Kernel.var!/1")

    fun testAMacroThatRaisesIsAnErrorAtItsCall() =
        assertEvery("alias!(1)", "error alias_bang_function_clause `alias!(1)` | imported_macro Elixir.Kernel.alias!/1")

    /** `var!/2` expands its context with `Macro.expand/2`, which stops at a macro with no summary. */
    fun testAMacroWhoseOwnExpansionStopsStopsThere() {
        macros = listOf(Env.Imports(OTHER, listOf(NameArity("foo", 1))))

        assertEvery(
            "var!(x, foo(1))",
            "opaque imported_macro Elixir.Other.foo/1 `foo(1)` | imported_macro Elixir.Kernel.var!/2",
        )
    }

    fun testAKernelMacroIsItsOutput() =
        assertEvery("if c, do: 1", "node `if c, do: 1` | imported_macro Elixir.Kernel.if/2")

    fun testAKernelMacroTakesOneCounterAndItsOutputGainsNoLineFromTheCall() {
        output = true

        assertEvery("\nif c, do: 1", "case, no line, 1 counter")
    }

    /** Before 1.20 `unless` gives an `if`, which `Macro.expand/2` expands in turn. */
    fun testEachMacroOfAnExpansionTakesACounter() {
        output = true

        assertSplit("unless c, do: 1", "1.20.0-rc.2", "case, no line, 2 counters", "case, no line, 1 counter")
    }

    // Remote calls

    fun testARequiredRemoteMacroWithoutASummaryIsOpaque() {
        requires = listOf(INTEGER)

        assertEvery("Integer.is_odd(1)", "opaque remote_macro Elixir.Integer.is_odd/1 `Integer.is_odd(1)` |")
    }

    fun testAnUnrequiredRemoteMacroIsAnErrorThenUnseenThenAFunction() =
        assertLevels("Integer.is_odd(1)", LEVELS + "1.12.2") { version ->
            when {
                isBefore(version, "1.12.2") -> "error unrequired_module `Integer.is_odd(1)` |"
                isBefore(version, "1.13.0-rc.0") -> "unported `Integer.is_odd(1)` |"
                else -> UNCHANGED
            }
        }

    fun testARequiredUnreadableModuleIsUnported() {
        requires = listOf(UNREADABLE)

        assertEvery("Unreadable.foo(1)", "unported `Unreadable.foo(1)` |")
    }

    fun testARemoteFunctionIsUnchangedAndUntraced() = assertEvery("List.first([1])", UNCHANGED)

    fun testARemoteCallOfAnAbsentModuleIsUnchanged() = assertEvery("Foo.bar(1)", UNCHANGED)

    fun testARemoteCallOfAVariableIsUnchanged() = assertEvery("x.foo(1)", UNCHANGED)

    fun testARemoteCallsReceiverIsExpandedOnce() =
        assertEvery("inspect(1).foo()", "unchanged | imported_function Elixir.Kernel.inspect/1")

    /** The module being defined is always required. */
    fun testARemoteCallOfModuleIsOfTheEnvsModule() =
        assertEvery("__MODULE__.foo(1)", "opaque remote_macro Elixir.Case.foo/1 `__MODULE__.foo(1)` |")

    // Rendering

    override fun expandAndRender(code: String, version: String): String {
        val level = ElixirLanguageLevel.of(version)
        val dispatched = mutableListOf<String>()
        val observer = object : ExpansionObserver {
            override fun entering(node: ElixirAst, state: ExState, env: Env) {}

            override fun dispatched(node: ElixirAst, dispatch: Dispatch) {
                dispatched.add(render(dispatch))
            }
        }
        val empty = Env.empty(level, kernel)
        val env = empty.copy(
            aliases = aliases,
            requires = (empty.requires + requires).sorted(),
            functions = empty.functions + functions,
            macros = empty.macros + macros,
            module = module,
        )
        val run = Run(level, observer, exports, structs)
        val expanded = macroExpand(lower(code, level), ExState.empty(level), env, run)

        if (output) {
            val node = (expanded as MacroExpanded.Node).node as ElixirAst.Call
            val name = (node.callee as ElixirAst.Literal.Atom).name
            val line = if (node.meta.keys.any { it is Meta.Key.Location }) "line" else "no line"
            val counters = run.counters.count(module)

            return "$name, $line, $counters counter${if (counters == 1L) "" else "s"}"
        }

        return (render(code, expanded) + " | " + dispatched.joinToString()).trimEnd()
    }

    private fun render(code: String, expanded: MacroExpanded): String =
        when (expanded) {
            is MacroExpanded.Node -> {
                val node = expanded.node

                when {
                    !expanded.expanded -> "unchanged"
                    node is ElixirAst.Literal.Atom -> "atom ${node.name}"
                    node is ElixirAst.Literal.Integer -> "integer ${node.value}"
                    else -> "node `${node.meta.origin.substring(code)}`"
                }
            }
            MacroExpanded.Dir -> "dir"
            is MacroExpanded.Stopped -> render(code, expanded.expansion)
        }

    private fun imports(arity: Int, module: String) =
        entry(
            "imports",
            Meta.Value.List(listOf(Meta.Value.Tuple(listOf(Meta.Value.Integer(arity.toLong()), atom(module))))),
        )

    private companion object {
        const val MODULE = "Elixir.Case"
        const val KERNEL = "Elixir.Kernel"
        const val LIST = "Elixir.List"
        const val INTEGER = "Elixir.Integer"
        const val OTHER = "Elixir.Other"
        const val UNREADABLE = "Elixir.Unreadable"
        const val FIRST = "first([1])"
        const val UNCHANGED = "unchanged |"
        val CONTEXT = Meta.Key.Entry("context", Meta.Value.Atom("Elixir.Quoter"))
    }
}
