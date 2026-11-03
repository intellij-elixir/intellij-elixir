package org.elixir_lang.expander

/**
 * Calls into a module the project doesn't have, each its own case, with the leg's modules' clauses as a project's SDK
 * gives them: nothing before the call declines, so the module isn't one that code the expander didn't follow may have
 * created, and the case compiles as Elixir compiles it. Before v1.13 Elixir reads an unrequired receiver's macros, which
 * a module that isn't loaded doesn't have.
 */
class AbsentReceiverProbeTest : SdkProbeTestCase(accounting = true) {
    fun testACallOnAnAliasOfANestedMissingModuleDispatchesAsAFunction() {
        assertDispatchesAsElixirDoes("_ = fn -> (alias Foo.Bar).baz() end")
    }

    fun testACallOnAnAliasOfAMissingModuleDispatchesAsAFunction() {
        assertDispatchesAsElixirDoes("_ = fn -> (alias Foo).baz() end")
    }

    fun testACallOnAnAliasWithOptionsOfAMissingModuleDispatchesAsAFunction() {
        assertDispatchesAsElixirDoes("_ = fn -> (alias Foo.Bar, warn: false).baz() end")
    }

    fun testACaptureOfAMissingModulesFunctionIsARemoteFunction() {
        assertDispatchesAsElixirDoes("_ = &NoSuchMod.foo/1")
    }

    /** Before v1.13 `in` binds a call on its right to a variable it names with `Integer.to_string/1`. */
    fun testAnInOfACallIntoAMissingModuleMatchesElixir() {
        val body = "x = 1\n_ = fn -> x in Foo.bar() end"
        val expansion = expand(body, "")

        assertTrue("$body: ${expansion.outcome}", expansion.outcome is Expansion.Expanded)
        printed { probes.assertMatchesElixir(mapOf(body to expansion)) }
        assertDispatchesAsElixirDoes(body)
    }

    private fun assertDispatchesAsElixirDoes(body: String) {
        printed { probes.assertTracesMatchElixir(probes.expandAll(listOf(body), clauses = sdk)) }
    }
}
