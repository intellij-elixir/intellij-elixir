package org.elixir_lang.expander

import org.elixir_lang.expander.StructEvents.BOOTSTRAP_AT
import org.elixir_lang.expander.StructEvents.BOOTSTRAP_DEF
import org.elixir_lang.expander.StructEvents.DEFSTRUCT_QUOTED
import org.elixir_lang.expander.StructEvents.INSPECTED
import org.elixir_lang.expander.StructEvents.KERNEL_AT
import org.elixir_lang.expander.StructEvents.put
import org.elixir_lang.language_level.ElixirLanguageLevel

/**
 * `defexception`: the struct it defines, the `message/1` and binary `exception/1` it adds for a `:message` field, the
 * `exception/1` it always adds, and the dispatches of the output Elixir expands it to on each release. Both
 * `defoverridable` calls take their definitions out of the table, and the end of the module body stores them back.
 */
class DefexceptionMacroTest : ExpanderTestCase() {
    override val exports: Exports = DefstructFixtures.EXPORTS
    override val kernel: KernelImports = DefstructFixtures.KERNEL

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
                "def __struct__/1 line 3 clauses 1",
        )

    fun testFieldsThatAreNotAtomsRaiseAtTheDefexception() =
        assertEvery(
            "defmodule A do\n  defexception [\"a\"]\nend",
            "module Elixir.A raised struct_field_not_atom `defexception [\"a\"]`",
        )

    fun testFieldsTheExpanderCannotFollowLeaveTheOverridableDefinitionsOut() =
        assertEvery(
            "defmodule A do\n  defexception Foo.fields()\nend",
            "module Elixir.A stopped `defexception Foo.fields()`$STRUCT",
        )

    fun testTheStructHoldsTheExceptionKey() =
        assertEvery(
            "defmodule A do\n  defexception [:reason]\n  defmodule B do\n    def f, do: %A{reason: 1, __exception__: true}\n  end\nend",
            "module Elixir.A compiled$STRUCT$EXCEPTION_1",
        )

    fun testAKeyThatIsNoFieldIsUnknown() =
        assertEvery(
            "defmodule A do\n  defexception [:reason]\n  defmodule B do\n    def f, do: %A{message: 1}\n  end\nend",
            "module Elixir.A raised struct_unknown_key `%A{message: 1}`$STRUCT",
        )

    /** The output's statements run in order, `struct = defstruct(...)` and the `if` branch Elixir takes among them. */
    fun testTheDefinitionsOfAnExceptionAreOrdered() {
        val code = "defmodule A do\n  defexception [:message]\nend"
        val ordered = { version: String ->
            val level = ElixirLanguageLevel.of(version)
            val module = Expander.expandFile(lower(code, level), Env.empty(level, kernel), level, exports, structs).modules.single()

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
        val file = Expander.expandFile(lower(code, level), Env.empty(level, kernel), level, exports, structs)

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
