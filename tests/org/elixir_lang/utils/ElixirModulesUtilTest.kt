package org.elixir_lang.utils

import org.elixir_lang.junit.UnitTestCase

class ElixirModulesUtilTest : UnitTestCase() {
    fun testValidElixirModuleName() {
        assertEquals("Benchfella", ElixirModulesUtil.erlangModuleNameToElixir("Elixir.Benchfella"))
    }

    fun testValidNestedElixirModuleName() {
        assertEquals("Benchfella.Snapshot", ElixirModulesUtil.erlangModuleNameToElixir("Elixir.Benchfella.Snapshot"))
    }

    fun testAtomWithColonAfterElixirPrefix() {
        // @bench_tab :"#{__MODULE__}:tests" produces atom 'Elixir.Benchfella:tests'
        // This is NOT a valid alias - must be rendered as the whole quoted atom
        assertEquals(":\"Elixir.Benchfella:tests\"", ElixirModulesUtil.erlangModuleNameToElixir("Elixir.Benchfella:tests"))
    }

    fun testAtomWithSpaceAfterElixirPrefix() {
        assertEquals(":\"Elixir.Foo bar\"", ElixirModulesUtil.erlangModuleNameToElixir("Elixir.Foo bar"))
    }

    fun testErlangAtom() {
        // Erlang atoms that don't start with Elixir. get a colon prefix
        assertEquals(":ets", ElixirModulesUtil.erlangModuleNameToElixir("ets"))
    }

    fun testSpecialAtoms() {
        assertEquals("true", ElixirModulesUtil.erlangModuleNameToElixir("true"))
        assertEquals("false", ElixirModulesUtil.erlangModuleNameToElixir("false"))
        assertEquals("nil", ElixirModulesUtil.erlangModuleNameToElixir("nil"))
    }

    fun testAtomWithQuoteAfterElixirPrefix() {
        // Atom containing a double-quote should be escaped
        assertEquals(":\"Elixir.Foo\\\"Bar\"", ElixirModulesUtil.erlangModuleNameToElixir("Elixir.Foo\"Bar"))
    }
}
