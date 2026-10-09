package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.declaration.MacroKey
import org.elixir_lang.declaration.MacroRole
import org.elixir_lang.junit.logs.UnexpectedLogsRule
import org.elixir_lang.language_level.ElixirLanguageFeature.NULLARY_RANGE
import org.elixir_lang.language_level.ElixirLanguageFeature.STEP_OPERATOR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** [MacroKey] against the leg's own exports, [MacroRole]'s names, and the summaries the registry has so far. */
class MacroKeyTest {
    @get:Rule
    val unexpectedLogs = UnexpectedLogsRule()

    @Test
    fun `every arity the leg's Kernel exports a summarised name at is a key`() {
        val summarised = MacroKey.entries.filter { it.receiver == KERNEL }.map { it.macroName }.toSet()
        val missing = legKernel.macros
            .filter { it.name in summarised }
            .filter { MacroKey.of(KERNEL, it.name, it.arity) == null }

        assertEquals("Kernel macros with no MacroKey", emptyList<NameArity>(), missing)
    }

    @Test
    fun `every key is a macro its receiver exports on the leg, unless the leg predates it`() {
        val level = legLevel()
        val predates = buildSet {
            if (!STEP_OPERATOR.isSufficient(level)) add(MacroKey.STEP_RANGE)
            if (!NULLARY_RANGE.isSufficient(level)) add(MacroKey.FULL_RANGE)
        }
        val unexported = MacroKey.entries.filter { key ->
            val macros = when (val exports = legExports.of(key.receiver)) {
                is ModuleExports.Present -> exports.macros
                ModuleExports.Absent, ModuleExports.Unreadable -> throw AssertionError("${key.receiver}: $exports")
            }

            NameArity(key.macroName, key.arity) !in macros
        }

        assertEquals("keys the leg doesn't export as macros", predates.toList(), unexported)
    }

    @Test
    fun `the keys of one name share its block`() {
        val split = MacroKey.entries.groupBy { it.receiver to it.macroName }.filterValues { keys ->
            keys.map { it.block }.distinct().size > 1
        }

        assertEquals("names whose keys have different blocks", emptyMap<Any, Any>(), split)
    }

    @Test
    fun `MacroRole's summaries are the names of Kernel's keys, with their blocks`() {
        for (key in MacroKey.entries.filter { it.receiver == KERNEL }) {
            assertEquals(key.toString(), MacroRole(MacroRole.Modelling.SUMMARY, key.block), MacroRole.listed(key.macroName))
        }

        for (name in listOf("!", "..", "..//", "<>", "and", "or", "raise", "to_string")) {
            assertEquals(name, MacroRole.Modelling.SUMMARY, MacroRole.listed(name)?.modelling)
        }
    }

    @Test
    fun testEverySummaryHasARegistryEntry() {
        val due = DUE.values.flatten()
        val missing = due.filter { Summaries.of(it) == null }
        val unlisted = MacroKey.entries.filter { Summaries.of(it) != null && it !in due }

        assertEquals("keys due a summary that have none", emptyList<MacroKey>(), missing)
        assertTrue("keys with a summary that no group lists: $unlisted", unlisted.isEmpty())
    }

    private companion object {
        const val KERNEL = "Elixir.Kernel"

        /** The keys each merged expander change summarises. */
        val DUE: Map<String, List<MacroKey>> = mapOf(
            "hygiene" to listOf(MacroKey.VAR_BANG_1, MacroKey.VAR_BANG_2, MacroKey.ALIAS_BANG),
            "modules and definitions" to listOf(
                MacroKey.DEF_1, MacroKey.DEF_2, MacroKey.DEFP_1, MacroKey.DEFP_2, MacroKey.DEFMACRO_1,
                MacroKey.DEFMACRO_2, MacroKey.DEFMACROP_1, MacroKey.DEFMACROP_2, MacroKey.DEFMODULE,
            ),
            "module attributes" to listOf(MacroKey.AT, MacroKey.BOOTSTRAP_AT),
            "definition macros" to listOf(
                MacroKey.DEFOVERRIDABLE, MacroKey.DEFGUARD, MacroKey.DEFGUARDP, MacroKey.UTILS_DEFGUARD,
                MacroKey.DEFSTRUCT, MacroKey.DEFEXCEPTION, MacroKey.BOOTSTRAP_DEF, MacroKey.DEFDELEGATE,
                MacroKey.DEFPROTOCOL, MacroKey.PROTOCOL_DEF, MacroKey.DEFIMPL_2, MacroKey.DEFIMPL_3,
            ),
            "kernel control flow" to listOf(
                MacroKey.IF, MacroKey.UNLESS, MacroKey.AND_AND, MacroKey.OR_OR, MacroKey.NOT, MacroKey.AND, MacroKey.OR,
                MacroKey.PIPE, MacroKey.IN, MacroKey.CONCAT, MacroKey.TO_STRING, MacroKey.RAISE_1, MacroKey.RAISE_2,
                MacroKey.BINDING_0, MacroKey.BINDING_1, MacroKey.DESTRUCTURE, MacroKey.RANGE, MacroKey.STEP_RANGE,
                MacroKey.FULL_RANGE,
            ),
            "use and the sigils" to listOf(
                MacroKey.USE_1, MacroKey.USE_2, MacroKey.SIGIL_C_UPPER, MacroKey.SIGIL_D, MacroKey.SIGIL_N,
                MacroKey.SIGIL_R_UPPER, MacroKey.SIGIL_S_UPPER, MacroKey.SIGIL_T, MacroKey.SIGIL_U,
                MacroKey.SIGIL_W_UPPER, MacroKey.SIGIL_C, MacroKey.SIGIL_R, MacroKey.SIGIL_S, MacroKey.SIGIL_W,
            ),
        )
    }
}
