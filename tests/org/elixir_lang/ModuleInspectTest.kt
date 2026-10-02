package org.elixir_lang

import org.elixir_lang.junit.UnitTestCase

/** [Module.inspect] writes the module an index name stands for the way Elixir's `inspect/1` writes its atom. */
class ModuleInspectTest : UnitTestCase() {
    fun testElixirPrefixedAtomThatIsNotAnAliasKeepsItsPrefix() =
        assertEquals(":\"Elixir.my-mod\"", Module.inspect(Module.indexName("Elixir.my-mod")))

    fun testAlias() = assertEquals("Foo.Bar", Module.inspect("Foo.Bar"))

    fun testAtomThatNeedsQuoting() = assertEquals(":\"my-mod\"", Module.inspect(Module.indexName("my-mod")))

    fun testCapitalisedAtom() = assertEquals(":Foo", Module.inspect(Module.indexName("Foo")))

    fun testNameWithNoValue() = assertEquals("?.Inner", Module.inspect("?.Inner"))
}
