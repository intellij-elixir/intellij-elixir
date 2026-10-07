package org.elixir_lang.expander

import org.elixir_lang.lowering.ElixirAst

/**
 * What each call dispatches to, in the order [ExpansionObserver.dispatched] is told, as the expanded receiver decides
 * it: an atom dispatches, and a tuple is a call at run time.
 */
class ReceiverValueTest : ExpanderTestCase() {
    override val exports: Exports = CallFixtures.EXPORTS
    override val kernel: KernelImports = CallFixtures.KERNEL
    override val module: String = "Elixir.Case"

    fun testAnImportedFunctionDispatchesInlined() =
        assertEvery("x = 1\nx + 1", "expanded {x:0} next 1 | imported_function erlang.+/2")

    fun testARemoteFunctionDispatchesInlined() =
        assertEvery("Integer.to_string(1)", "expanded {} next 0 | remote_function erlang.integer_to_binary/1")

    fun testAnImportedFunctionThatIsNotInlined() =
        assertEvery("t = {1}\nelem(t, 0)", "expanded {t:0} next 1 | imported_function Elixir.Kernel.elem/2")

    fun testACallDispatchesBeforeItsArguments() =
        assertEvery(
            "t = {1}\nabs(elem(t, 0) + 1)",
            "expanded {t:0} next 1 | imported_function erlang.abs/1, imported_function erlang.+/2, " +
                "imported_function Elixir.Kernel.elem/2",
        )

    fun testARemoteCallDispatchesBeforeItsArguments() =
        assertEvery(
            "m = %{}\nMap.get(m, String.length(\"a\"))",
            "expanded {m:0} next 1 | remote_function Elixir.Map.get/2, remote_function Elixir.String.length/1",
        )

    fun testAnErlangModule() =
        assertEvery("l = []\n:lists.reverse(l)", "expanded {l:0} next 1 | remote_function lists.reverse/1")

    fun testAMapLookupAndAnAnonymousCallDispatchNothing() {
        assertEvery("m = %{a: 1}\nm.a", "expanded {m:0} next 1 | ")
        assertSplit("f = fn x -> x end\nf.(1)", FN_VERSION, "expanded {f:1} next 2 | ", "expanded {f:2} next 3 | ")
    }

    fun testAMacroIsNotDispatchedWithoutASummary() =
        assertEvery("is_nil(1)", "opaque imported_macro Elixir.Kernel.is_nil/1 `is_nil(1)` | ")

    fun testTheModuleIsAReceiver() {
        assertInFn("__MODULE__.foo()") { "remote_function Elixir.Case.foo/0" }
        assertInFn("__ENV__.module.foo()") { "remote_function Elixir.Case.foo/0" }
    }

    /** From 1.18 an `alias` or `import` in a module body is the call that warns at run time, which is a tuple. */
    fun testAnAliasOrImportReceiver() {
        assertInFn("(alias Foo.Bar).baz()", "1.18.0-rc.0") {
            if (isBefore(it, "1.18.0-rc.0")) "remote_function Elixir.Foo.Bar.baz/0" else ""
        }
        assertInFn("(import Integer, only: [parse: 1]).baz()", "1.18.0-rc.0") {
            if (isBefore(it, "1.18.0-rc.0")) "remote_function Elixir.Integer.baz/0" else ""
        }
    }

    /** From 1.20 a `require` in a module body is the call that warns at run time. */
    fun testARequireReceiver() =
        assertInFn("(require Integer).baz()", "1.20.0-rc.0") {
            if (isBefore(it, "1.20.0-rc.0")) "remote_function Elixir.Integer.baz/0" else ""
        }

    fun testAnAliasThatDoesNotWarnIsAReceiver() {
        assertInFn("(alias Foo).baz()") { "remote_function Elixir.Foo.baz/0" }
        assertInFn("(alias Foo.Bar, warn: false).baz()") { "remote_function Elixir.Foo.Bar.baz/0" }
    }

    /** Inside `rescue`, up to 1.13, `System.stacktrace()` is `__STACKTRACE__`, and dispatches nothing. */
    fun testSystemStacktraceInARescue() {
        val code = "try do\n1\nrescue\n_ -> System.stacktrace()\nend"

        assertEquals(
            LEVELS.joinToString("\n") {
                "$it: " + if (isBefore(it, "1.14.0-rc.0")) "" else "remote_function Elixir.System.stacktrace/0"
            },
            LEVELS.joinToString("\n") { "$it: " + dispatches(code, it).second },
        )
    }

    fun testSystemStacktraceOutsideARescue() =
        assertEvery("System.stacktrace()", "expanded {} next 0 | remote_function Elixir.System.stacktrace/0")

    /**
     * [body], in an `fn` that keeps it from running, dispatches what [dispatches] gives at each version in [LEVELS] and
     * at [boundary]. The `fn` takes a version from [FN_VERSION].
     */
    private fun assertInFn(body: String, boundary: String? = null, dispatches: (String) -> String) =
        assertLevels("fn -> $body end", LEVELS + listOfNotNull(boundary, FN_VERSION)) { version ->
            "expanded {} next ${if (isBefore(version, FN_VERSION)) 0 else 1} | ${dispatches(version)}"
        }

    /** [code]'s expansion, then each dispatch it was told of. */
    override fun expandAndRender(code: String, version: String): String =
        dispatches(code, version).let { (expansion, dispatches) -> "${render(code, expansion)} | $dispatches" }

    private fun dispatches(code: String, version: String): Pair<Expansion, String> {
        val dispatches = mutableListOf<Dispatch>()
        val observer = object : ExpansionObserver {
            override fun entering(node: ElixirAst, state: ExState, env: Env) {}

            override fun dispatched(node: ElixirAst, dispatch: Dispatch) {
                dispatches.add(dispatch)
            }
        }
        val expansion = expand(code, version, observer)

        return expansion to dispatches.joinToString(", ", transform = ::render)
    }

    private companion object {
        /** `CLAUSES_TAKE_VERSION`: from it an `fn` takes a version. */
        const val FN_VERSION = "1.20.0-rc.5"
    }
}
