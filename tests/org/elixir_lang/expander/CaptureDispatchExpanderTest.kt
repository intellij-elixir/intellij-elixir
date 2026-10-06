package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst

/**
 * Captures of named functions, which `elixir_dispatch:import_function/4` and `require_function/5` look up: each gives
 * its expansion, then the dispatches it reports.
 */
class CaptureDispatchExpanderTest : ExpanderTestCase() {
    override val exports: Exports = CallFixtures.EXPORTS
    override val kernel: KernelImports = CallFixtures.KERNEL
    override val module: String = "Elixir.Capturing"
    override var function: NameArity? = null

    // Imports

    fun testACaptureOfAnImportedFunction() {
        assertCaptured("&abs/1", "expanded {} next 0 | imported_function erlang.abs/1")
        assertCaptured("&abs(&1)", "expanded {} next 0 | imported_function erlang.abs/1")
        assertCaptured("&(&1 + &2)", "expanded {} next 0 | imported_function erlang.+/2")
    }

    /**
     * A macro isn't captured: the `fn` Elixir rewrites the capture to calls it. `&f/a`'s call has the `&`'s position
     * until 1.14.0-rc.1, and the name's from it.
     */
    fun testACaptureOfAnImportedMacro() {
        val macro = "imported_macro Elixir.Kernel.to_string/1"

        assertCapturedSplit("&to_string/1", NAME_META, "opaque $macro `&to_string/1` | ", "opaque $macro `to_string` | ")
        assertCaptured("&to_string(&1)", "opaque $macro `to_string(&1)` | ")
    }

    fun testACaptureOfAnAmbiguousImport() =
        assertCapturedSplit(
            "import M, only: [f: 1]\nimport :x3e, only: [f: 1]\n&f/1",
            NAME_META,
            "error ambiguous_call `&f/1` | ",
            "error ambiguous_call `f` | "
        )

    /** With no import and no function, the capture is of a local function the module body can't have. */
    fun testACaptureOfALocalFunctionInAModuleBody() {
        assertCaptured("&foo/1", "error undefined_local_capture `&foo/1` | local_function Elixir.Capturing.foo/1")
        assertCaptured("&foo(&1)", "error undefined_local_capture `&foo(&1)` | local_function Elixir.Capturing.foo/1")
    }

    /** Inside a function the capture is kept for the checks once the module's body has run, as a call is. */
    fun testACaptureOfALocalFunctionInAFunction() {
        function = NameArity("f", 0)

        assertCaptured("&foo/1", "expanded {} next 0 | local_function Elixir.Capturing.foo/1")
    }

    // Remotes

    fun testACaptureOfARemoteFunction() {
        assertCaptured("&Integer.to_string/1", "expanded {} next 0 | remote_function erlang.integer_to_binary/1")
        assertCaptured("&Integer.to_string(&1)", "expanded {} next 0 | remote_function erlang.integer_to_binary/1")
        assertCaptured("&:lists.reverse/1", "expanded {} next 0 | remote_function lists.reverse/1")
        assertCaptured("&NoSuchMod.foo/1", "expanded {} next 0 | remote_function Elixir.NoSuchMod.foo/1")
        assertCaptured("&__MODULE__.foo/0", "expanded {} next 0 | remote_function Elixir.Capturing.foo/0")
    }

    fun testACaptureOnAVariableReceiverDispatchesNothing() =
        assertCaptured("m = URI\n&m.parse/1", "expanded {m:0} next 1 | ")

    fun testACaptureOfARequiredMacro() {
        val macro = "remote_macro Elixir.Integer.is_odd/1"

        assertCapturedSplit(
            "require Integer\n&Integer.is_odd/1",
            NAME_META,
            "opaque $macro `&Integer.is_odd/1` | ",
            "opaque $macro `Integer.is_odd` | "
        )
    }

    /**
     * Up to 1.12 an unrequired module's macros count only if the module is loaded, which the expander can't know; from
     * 1.13 an unrequired module has none.
     */
    fun testACaptureOfAMacroOfAModuleThatIsNotRequired() {
        assertCapturedSplit(
            "&Integer.is_odd/1",
            "1.13.0-rc.0",
            "unported `&Integer.is_odd/1` | ",
            "expanded {} next 0 | remote_function Elixir.Integer.is_odd/1"
        )
        assertCapturedSplit(
            "&Record.is_record(&1)",
            "1.13.0-rc.0",
            "unported `&Record.is_record(&1)` | ",
            "expanded {} next 0 | remote_function Elixir.Record.is_record/1"
        )
    }

    fun testACaptureOfAFunctionOfAModuleThatIsNotRequired() =
        assertCaptured("&Record.extract/2", "expanded {} next 0 | remote_function Elixir.Record.extract/2")

    fun testACaptureOfAModuleWhoseExportsCannotBeRead() =
        assertCapturedSplit(
            "&U.f/1",
            "1.13.0-rc.0",
            "unported `&U.f/1` | ",
            "expanded {} next 0 | remote_function Elixir.U.f/1"
        )

    private fun assertCaptured(code: String, expected: String) =
        assertEquals(LEVELS.joinToString("\n") { "$it: $expected" }, captured(code, LEVELS))

    private fun assertCapturedSplit(code: String, boundary: String, before: String, from: String) {
        val versions = (LEVELS + boundary).sortedBy { ElixirLanguageLevel.of(it).elixir }

        assertEquals(
            versions.joinToString("\n") { "$it: ${if (isBefore(it, boundary)) before else from}" },
            captured(code, versions)
        )
    }

    private fun captured(code: String, versions: List<String>): String =
        versions.joinToString("\n") { version ->
            val events = mutableListOf<String>()
            val observer = object : ExpansionObserver {
                override fun entering(node: ElixirAst, state: ExState, env: Env) {}

                override fun dispatched(node: ElixirAst, dispatch: Dispatch) {
                    events.add(render(dispatch))
                }
            }

            "$version: " + render(code, expand(code, version, observer)) + " | " + events.joinToString(" ")
        }

    private companion object {
        /** `elixir-lang/elixir@6c068176d`: `&f/a` and `&M.f/a` build their call with the name's metadata. */
        const val NAME_META = "1.14.0-rc.1"
    }
}
