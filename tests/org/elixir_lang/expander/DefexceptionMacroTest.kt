package org.elixir_lang.expander

import org.elixir_lang.expander.StructEvents.BOOTSTRAP_AT
import org.elixir_lang.expander.StructEvents.BOOTSTRAP_DEF
import org.elixir_lang.expander.StructEvents.DEFSTRUCT_QUOTED
import org.elixir_lang.expander.StructEvents.INSPECTED
import org.elixir_lang.expander.StructEvents.KERNEL_AT
import org.elixir_lang.expander.StructEvents.put
import org.elixir_lang.language_level.ElixirLanguageFeature.DEFSTRUCT_BIND_QUOTED
import org.elixir_lang.language_level.ElixirLanguageLevel

/**
 * `defexception`: the struct it defines, the `message/1` and binary `exception/1` it adds for a `:message` field, the
 * `exception/1` it always adds, and the dispatches of the output Elixir expands it to on each release. Both
 * `defoverridable` calls take their definitions out of the table, and the end of the module body stores them back.
 */
class DefexceptionMacroTest : ExpanderTestCase() {
    override val exports: Exports = DefstructFixtures.EXPORTS
    override val kernel: KernelImports = DefstructFixtures.KERNEL

    /** The output's `Map.has_key?(struct, :message)` runs from `Map`'s clause. */
    override val clauses: ModuleClauses.Lookup by lazy {
        fixtureClauses("defmodule Map do\n  def has_key?(map, key), do: :maps.is_key(key, map)\nend")
    }

    fun testAnExceptionWithoutAMessageFieldDefinesTheExceptionFunction() =
        assertEvery("defmodule A do\n  defexception [:reason]\nend", "module Elixir.A compiled$STRUCT$EXCEPTION_1")

    fun testAMessageFieldAddsTheMessageFunctionAndTheBinaryClause() =
        assertEvery(
            "defmodule A do\n  defexception [:message]\nend",
            "module Elixir.A compiled$STRUCT$MESSAGE$EXCEPTION_2",
        )

    fun testADefaultedMessageFieldDoesToo() =
        assertEvery(
            "defmodule A do\n  defexception message: \"m\", reason: 1\nend",
            "module Elixir.A compiled$STRUCT$MESSAGE$EXCEPTION_2",
        )

    /** In the branch an `if` selects, the output runs as it does at a statement. */
    fun testADefexceptionInASelectedBranchDefinesAsAStatementDoes() =
        assertEvery(
            "defmodule A do\n  x = true\n  if x do\n    defexception [:message]\n  end\nend",
            "module Elixir.A compiled | def __struct__/0 line 4 clauses 1 | def __struct__/1 line 4 clauses 1 | def message/1 line 4 clauses 1 | def exception/1 line 4 clauses 2",
        )

    fun testTheMessageFunctionCanBeOverridden() =
        assertEvery(
            "defmodule A do\n  defexception [:message]\n  def message(e), do: e.message\nend",
            "module Elixir.A compiled$STRUCT | def message/1 line 3 clauses 1$EXCEPTION_2",
        )

    fun testTheMessageFunctionCanBeDefinedWithoutAMessageField() =
        assertEvery(
            "defmodule A do\n  defexception [:reason]\n  def message(e), do: e.reason\nend",
            "module Elixir.A compiled$STRUCT | def message/1 line 3 clauses 1$EXCEPTION_1",
        )

    fun testTheExceptionFunctionCanBeOverridden() =
        assertEvery(
            "defmodule A do\n  defexception [:reason]\n  def exception(args), do: args\nend",
            "module Elixir.A compiled$STRUCT | def exception/1 line 3 clauses 1",
        )

    fun testEnforcedKeysReachTheStruct() =
        assertEvery(
            "defmodule A do\n  @enforce_keys [:reason]\n  defexception [:reason]\n  def f, do: %A{}\nend",
            "module Elixir.A raised struct_missing_enforced_keys `%A{}` | def __struct__/0 line 3 clauses 1 | " +
                "def __struct__/1 line 3 clauses 1 | def exception/1 line 3 clauses 1",
        )

    fun testFieldsThatAreNotAtomsRaiseAtTheDefexception() =
        assertEvery(
            "defmodule A do\n  defexception [\"a\"]\nend",
            "module Elixir.A raised struct_field_not_atom `defexception [\"a\"]`",
        )

    /**
     * Fields the expander can't follow stop the module, which keeps the definitions made overridable before the stop.
     * Whether there is a `:message` field isn't known, so those its `if` branch adds are unordered, as both arms'
     * `__struct__/1` are up to 1.13, where the output's `case` on `@enforce_keys` is declined after the stop.
     */
    fun testFieldsTheExpanderCannotFollowStopTheModuleAfterTheOverridableDefinitions() {
        val code = "defmodule A do\n  defexception Foo.fields()\nend"
        val ordered = { version: String ->
            val level = ElixirLanguageLevel.of(version)
            val module = Expander.expandFile(lower(code, level), Env.empty(level, kernel), level, exports, structs, clauses = clauses).modules.single()

            module.table.entries.map { (nameArity, entry) -> "${nameArity.name}/${nameArity.arity} ${entry.ordered}" }
        }
        val expected = { version: String ->
            "[__struct__/0 true, __struct__/1 ${bindsQuoted(version)}, message/1 false, exception/1 false]"
        }

        assertLevels(code, LEVELS) { "module Elixir.A stopped `defexception Foo.fields()`${struct(it)}$MESSAGE$EXCEPTION_2" }
        assertEquals(
            LEVELS.joinToString("\n") { "$it: ${expected(it)}" },
            LEVELS.joinToString("\n") { "$it: ${ordered(it)}" },
        )
    }

    /** Whether the output of `defstruct` binds its struct with `bind_quoted`, from 1.14: before it, a `case` on `@enforce_keys` defines `__struct__/1`. */
    private fun bindsQuoted(version: String): Boolean = DEFSTRUCT_BIND_QUOTED.isSufficient(ElixirLanguageLevel.of(version))

    /** [STRUCT] for a module that stopped at the `defexception`: up to 1.13 both arms' `__struct__/1` are stored. */
    private fun struct(version: String): String =
        " | def __struct__/0 line 2 clauses 1 | def __struct__/1 line 2 clauses ${stoppedStructClauses(ElixirLanguageLevel.of(version))}"

    fun testTheStructHoldsTheExceptionKey() =
        assertEvery(
            "defmodule A do\n  defexception [:reason]\n  defmodule B do\n    def f, do: %A{reason: 1, __exception__: true}\n  end\nend",
            "module Elixir.A compiled$STRUCT$EXCEPTION_1",
        )

    fun testAKeyThatIsNoFieldIsUnknown() =
        assertEvery(
            "defmodule A do\n  defexception [:reason]\n  defmodule B do\n    def f, do: %A{message: 1}\n  end\nend",
            "module Elixir.A raised struct_unknown_key `%A{message: 1}`$STRUCT$EXCEPTION_1",
        )

    /** The output's statements run in order, `struct = defstruct(...)` and the `if` branch Elixir takes among them. */
    fun testTheDefinitionsOfAnExceptionAreOrdered() {
        val code = "defmodule A do\n  defexception [:message]\nend"
        val ordered = { version: String ->
            val level = ElixirLanguageLevel.of(version)
            val module = Expander.expandFile(lower(code, level), Env.empty(level, kernel), level, exports, structs, clauses = clauses).modules.single()

            module.units.filter { it.owner is ExpansionResult.Owner.Definition }.map { it.ordered }
        }

        assertEquals(
            LEVELS.joinToString("\n") { "$it: ${List(5) { true }}" },
            LEVELS.joinToString("\n") { "$it: ${ordered(it)}" },
        )
    }

    fun testTheEventsWithoutAMessageField() = assertEvents("defmodule A do\n  defexception [:reason]\nend", message = false)

    fun testTheEventsWithAMessageField() = assertEvents("defmodule A do\n  defexception [:message]\nend", message = true)

    fun testTheEventsOfDefaultedFields() =
        assertEvents("defmodule A do\n  defexception message: \"m\", reason: 1\nend", message = true)

    /** The dispatches of `defexception`'s expansion and of the definitions it stores, as Elixir traces them. */
    private fun assertEvents(code: String, message: Boolean) =
        assertEquals(
            LEVELS.joinToString("\n") { "$it: ${expected(it, message)}" },
            LEVELS.joinToString("\n") { "$it: " + events(code, it).dropWhile { event -> event != DEFEXCEPTION } },
        )

    private fun expected(version: String, message: Boolean): List<String> {
        val at = if (isBefore(version, "1.14.0-rc.0")) BOOTSTRAP_AT else KERNEL_AT
        val put = put(version)
        // `Kernel.++/2` and the `is_*` guards are traced as the Erlang functions they compile to.
        val struct = StructEvents.output(version, DEFSTRUCT_QUOTED, listOf("remote_function erlang.++/2"), enforced = false)
        val inFalseOrNil = if (isBefore(version, "1.20.0-rc.0")) listOf("remote_macro Elixir.Kernel.in/2") else emptyList()
        val condition = listOf(
            listOf("remote_macro Elixir.Kernel.if/2", "remote_function Elixir.Map.has_key?/2"),
            inFalseOrNil,
            listOf("remote_function erlang.orelse/2", "remote_function erlang.=:=/2", "remote_function erlang.=:=/2"),
        ).flatten()
        // Both branches of the `if` expand, then `exception/1`.
        val branch = listOf(at, put, BOOTSTRAP_DEF, DEFOVERRIDABLE, MAKE_OVERRIDABLE, at, put, BOOTSTRAP_DEF)
        val exception = listOf(at, put, BOOTSTRAP_DEF, DEFOVERRIDABLE, MAKE_OVERRIDABLE)
        val binary = if (message) listOf("remote_function erlang.is_binary/1", "local_function Elixir.A.exception/1") else emptyList()
        val bodies = listOf(
            StructEvents.bodies(version, enforced = false),
            binary,
            listOf("remote_function erlang.is_list/1"),
            exceptionBody(version),
        ).flatten()

        return listOf(listOf(DEFEXCEPTION, at, put), struct, condition, branch, exception, bodies).flatten()
    }

    /** `exception/1`'s body: a check of the unknown fields up to 1.17, which calls `__struct__/0`, and `struct!/2` after. */
    private fun exceptionBody(version: String): List<String> =
        if (isBefore(version, "1.18.0-rc.0")) {
            listOf(
                listOf(
                    "local_function Elixir.A.__struct__/0",
                    "remote_function Elixir.Enum.split_with/2",
                    "remote_function Elixir.Map.has_key?/2",
                    "remote_function Elixir.IO.warn/1",
                    "remote_macro Elixir.Kernel.<>/2",
                ),
                INSPECTED,
                INSPECTED,
                INSPECTED,
                listOf("remote_function Elixir.Kernel.struct!/2"),
            ).flatten()
        } else {
            listOf("remote_function Elixir.Kernel.struct!/2")
        }

    override fun expandAndRender(code: String, version: String): String {
        val level = ElixirLanguageLevel.of(version)
        val file = Expander.expandFile(lower(code, level), Env.empty(level, kernel), level, exports, structs, clauses = clauses)

        return file.modules.joinToString("\n") { ModuleRendering.render(code, it) }
    }

    private companion object {
        const val DEFEXCEPTION = "imported_macro Elixir.Kernel.defexception/1"
        const val DEFOVERRIDABLE = "remote_macro Elixir.Kernel.defoverridable/1"
        const val MAKE_OVERRIDABLE = "remote_function Elixir.Module.make_overridable/2"

        const val STRUCT = " | def __struct__/0 line 2 clauses 1 | def __struct__/1 line 2 clauses 1"
        const val MESSAGE = " | def message/1 line 2 clauses 1"
        const val EXCEPTION_1 = " | def exception/1 line 2 clauses 1"
        const val EXCEPTION_2 = " | def exception/1 line 2 clauses 2"
    }
}
