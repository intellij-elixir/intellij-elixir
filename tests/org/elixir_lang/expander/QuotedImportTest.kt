package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Meta

/**
 * A call carrying the import `quote` recorded in its metadata, as macro output does: before 1.14 `import: Module`, from
 * 1.14 `imports: [{arity, Module}]`, each read only beside `context:`. Each expansion renders with the dispatches it
 * traced.
 */
class QuotedImportTest : ExpanderTestCase() {
    override val exports: Exports = Exports { module ->
        when (module) {
            KERNEL -> ModuleExports.Present(listOf(NameArity("length", 1)), emptyList(), hasInfo = true)
            LIST ->
                ModuleExports.Present(listOf(NameArity("first", 1), NameArity("first", 2)), emptyList(), hasInfo = true)
            INTEGER -> ModuleExports.Present(emptyList(), listOf(NameArity("is_odd", 1)), hasInfo = true)
            UNREADABLE -> ModuleExports.Unreadable
            else -> ModuleExports.Absent
        }
    }
    override val module: String = "Elixir.Case"

    // The era

    fun testImportsIsReadFrom1_14() =
        withKeys(FIRST to listOf(CONTEXT, imports(1 to LIST))) {
            assertSplit(FIRST, "1.14.0-rc.0", UNDEFINED, REMOTE_FIRST)
        }

    fun testImportIsReadBefore1_14() =
        withKeys(FIRST to listOf(CONTEXT, import(LIST))) {
            assertSplit(FIRST, "1.14.0-rc.0", REMOTE_FIRST, UNDEFINED)
        }

    fun testNoImportWithoutContext() =
        withKeys(FIRST to listOf(import(LIST), imports(1 to LIST))) { assertEvery(FIRST, UNDEFINED) }

    fun testImportsHasNoEntryForTheArity() =
        withKeys(FIRST to listOf(CONTEXT, imports(2 to LIST))) { assertEvery(FIRST, UNDEFINED) }

    fun testImportBefore1_14IsForEveryArity() =
        withKeys("first([1], 2)" to listOf(CONTEXT, import(LIST))) {
            assertSplit(
                "first([1], 2)",
                "1.14.0-rc.0",
                "expanded {} next 0 | remote_function Elixir.List.first/2",
                "error undefined_function `first([1], 2)` |",
            )
        }

    fun testAnEmptyImportsIsNoImport() =
        withKeys(FIRST to listOf(CONTEXT, importsOf())) {
            assertEvery(FIRST, UNDEFINED)
        }

    // The dispatch

    fun testAQuotedMacroIsARequiredRemoteMacro() =
        withKeys("is_odd(1)" to bothEras(INTEGER, 1)) {
            assertEvery("is_odd(1)", "opaque remote_macro Elixir.Integer.is_odd/1 `is_odd(1)` |")
        }

    fun testAQuotedFunctionIsARemoteFunctionWhereTheEnvImportsItToo() =
        withKeys(FIRST to bothEras(LIST, 1)) {
            assertWindow("import List, only: [first: 1]\n$FIRST", UNTRACED_SINCE, UNTRACED_REMOVED, REMOTE_FIRST, UNTRACED)
        }

    fun testAnImportedFunctionWithoutTheMetaIsAnImportedFunction() =
        assertEvery(
            "import List, only: [first: 1]\n$FIRST",
            "expanded {} next 0 | imported_function Elixir.List.first/1",
        )

    fun testAQuotedFunctionIsInlined() =
        withKeys("length([1])" to bothEras(KERNEL, 1)) {
            assertWindow(
                "length([1])",
                UNTRACED_SINCE,
                UNTRACED_REMOVED,
                "expanded {} next 0 | remote_function erlang.length/1",
                UNTRACED,
            )
        }

    /**
     * Elixir 1.18.0 to 1.18.3 trace no event for a quoted function: the remote re-expansion that traced it went in
     * 1.18.0-rc.0, and the arm traces it from 1.18.4.
     */
    fun testAQuotedFunctionIsUntracedOn1_18_0To1_18_3() =
        withKeys(FIRST to bothEras(LIST, 1)) {
            assertWindow(FIRST, UNTRACED_SINCE, UNTRACED_REMOVED, REMOTE_FIRST, UNTRACED)
        }

    fun testAQuotedReceiverWhoseExportsAreUnreadableIsUnported() =
        withKeys(FIRST to bothEras(UNREADABLE, 1)) { assertEvery(FIRST, "unported `$FIRST` |") }

    // The capture: `import_function/4`'s quoted arm is `require_function/5`, which traces on every leg. Before
    // 1.14.0-rc.1 the capture's call takes the `&`'s metadata, so no quoted import is seen.

    fun testACapturedQuotedFunctionIsARemoteFunction() =
        withKeys("first" to bothEras(LIST, 1)) {
            assertSplit(
                "&first/1",
                CAPTURE_META_KEPT,
                CAPTURE_UNDEFINED,
                "expanded {} next 0 | remote_function Elixir.List.first/1",
            )
        }

    fun testACapturedQuotedMacroOfARequiredModuleIsAnFnCallingIt() =
        withKeys("is_odd" to bothEras(INTEGER, 1)) {
            assertSplit(
                "require Integer\n&is_odd/1",
                CAPTURE_META_KEPT,
                "error undefined_local_capture `&is_odd/1` | local_function Elixir.Case.is_odd/1",
                "opaque remote_macro Elixir.Integer.is_odd/1 `is_odd` |",
            )
        }

    fun testACapturesQuotedImportIsReadFromTheAmpersandBefore1_14_0_rc_1() =
        withKeys("&first/1" to bothEras(LIST, 1)) {
            assertSplit(
                "&first/1",
                CAPTURE_META_KEPT,
                "expanded {} next 0 | remote_function Elixir.List.first/1",
                CAPTURE_UNDEFINED,
            )
        }

    fun testACapturedQuotedImportWithANonListImportsIsUnportedOn1_14To1_16() =
        withKeys("first" to listOf(CONTEXT, entry("imports", atom("bad")))) {
            assertWindow("&first/1", CAPTURE_META_KEPT, "1.17.0-rc.0", CAPTURE_UNDEFINED, "unported `&first/1` |")
        }

    // Malformed meta

    fun testANonListImportsIsACrashOn1_14To1_16AndNoImportFrom1_17() =
        withKeys(FIRST to listOf(CONTEXT, entry("imports", atom("bad")))) {
            assertWindow(FIRST, "1.14.0-rc.0", "1.17.0-rc.0", UNDEFINED, "unported `$FIRST` |")
        }

    fun testANonListImportsFallsThroughToTheEnvsImportFrom1_17() =
        withKeys(FIRST to listOf(CONTEXT, entry("imports", atom("bad")))) {
            assertWindow(
                "import List, only: [first: 1]\n$FIRST",
                "1.14.0-rc.0",
                "1.17.0-rc.0",
                "expanded {} next 0 | imported_function Elixir.List.first/1",
                "unported `$FIRST` |",
            )
        }

    fun testAnImportsEntryNotOfTwoIsUnported() =
        withKeys(FIRST to listOf(CONTEXT, importsOf(tuple(int(1), atom(LIST), atom("x"))))) {
            assertSplit(FIRST, "1.14.0-rc.0", UNDEFINED, "unported `$FIRST` |")
        }

    fun testAnImportsEntryWhoseModuleIsNotAnAtomIsUnported() =
        withKeys(FIRST to listOf(CONTEXT, importsOf(tuple(int(1), int(2))))) {
            assertSplit(FIRST, "1.14.0-rc.0", UNDEFINED, "unported `$FIRST` |")
        }

    fun testAnImportThatIsNotAnAtomIsUnported() =
        withKeys(FIRST to listOf(CONTEXT, entry("import", int(1)))) {
            assertSplit(FIRST, "1.14.0-rc.0", "unported `$FIRST` |", UNDEFINED)
        }

    // Rendering

    override fun expandAndRender(code: String, version: String): String {
        val dispatched = mutableListOf<String>()
        val expansion = expand(code, version, object : ExpansionObserver {
            override fun entering(node: ElixirAst, state: ExState, env: Env) {}

            override fun dispatched(node: ElixirAst, dispatch: Dispatch) {
                dispatched.add(render(dispatch))
            }
        })

        return (render(code, expansion) + " | " + dispatched.joinToString()).trimEnd()
    }

    private fun import(module: String) = entry("import", atom(module))

    private fun imports(vararg entries: Pair<Int, String>) =
        importsOf(*entries.map { (arity, module) -> tuple(int(arity), atom(module)) }.toTypedArray())

    private fun importsOf(vararg elements: Meta.Value) = entry("imports", Meta.Value.List(elements.toList()))

    /** The import of [module] at [arity] as each era's `quote` records it, so every leg reads it. */
    private fun bothEras(module: String, arity: Int) = listOf(CONTEXT, import(module), imports(arity to module))

    private fun tuple(vararg elements: Meta.Value) = Meta.Value.Tuple(elements.toList())

    private fun int(value: Int) = Meta.Value.Integer(value.toLong())

    private companion object {
        const val KERNEL = "Elixir.Kernel"
        const val LIST = "Elixir.List"
        const val INTEGER = "Elixir.Integer"
        const val UNREADABLE = "Elixir.Unreadable"
        const val FIRST = "first([1])"
        const val UNDEFINED = "error undefined_function `$FIRST` |"
        const val CAPTURE_META_KEPT = "1.14.0-rc.1"
        const val CAPTURE_UNDEFINED ="error undefined_local_capture `&first/1` | local_function Elixir.Case.first/1"
        const val REMOTE_FIRST = "expanded {} next 0 | remote_function Elixir.List.first/1"
        const val UNTRACED = "expanded {} next 0 |"
        const val UNTRACED_SINCE = "1.18.0-rc.0"
        const val UNTRACED_REMOVED = "1.18.4"
        val CONTEXT = Meta.Key.Entry("context", Meta.Value.Atom("Elixir.Quoter"))
    }
}
