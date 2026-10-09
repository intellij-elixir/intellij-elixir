package org.elixir_lang.expander

import org.elixir_lang.NameArity

/**
 * What expanding `use` goes on to do with the `require` and the `__using__` call it builds, which
 * [SigilOutputTest] compares only as built: the module's `__using__` has no summary, so it is `Opaque` at its dispatch.
 */
class UseMacroTest : ExpanderTestCase() {
    override val kernel = KernelImports(emptyList(), listOf(NameArity("use", 1), NameArity("use", 2)))

    override val exports = Exports { module ->
        when (module) {
            "Elixir.Foo" -> ModuleExports.Present(emptyList(), listOf(NameArity("__using__", 1)), hasInfo = true)
            "Elixir.Foo.Bar" -> ModuleExports.Present(emptyList(), listOf(NameArity("__using__", 1)), hasInfo = true)
            "Elixir.Bar" -> ModuleExports.Present(emptyList(), emptyList(), hasInfo = true)
            else -> ModuleExports.Absent
        }
    }

    fun testTheModulesUsingMacroIsOpaque() =
        assertEvery("use Foo", "opaque remote_macro Elixir.Foo.__using__/1 `use Foo`")

    fun testTheOptionsAreItsArgument() =
        assertEvery("use Foo, a: 1", "opaque remote_macro Elixir.Foo.__using__/1 `use Foo, a: 1`")

    /** `Foo.Bar` is loaded and `Foo.Baz` is not, so the expansion reaches `Bar`'s `__using__` only if `Bar` comes first. */
    fun testTheFirstOfSeveralModulesIsRequiredFirst() =
        assertEvery("use Foo.{Bar, Baz}", "opaque remote_macro Elixir.Foo.Bar.__using__/1 `use Foo.{Bar, Baz}`")

    /** The call to a function the module lacks is `Bar.__using__/1`'s run time failure, not the expansion's. */
    fun testAModuleWithNoUsingMacro() = assertEvery("use Bar", "expanded {} next 0")

    fun testAModuleThatIsNotLoaded() = assertEvery("use Nope", "error unloaded_module `use Nope`")

    fun testAModuleThatIsNotAnAliasIsAnError() = assertEvery("use 1", "error use_invalid_arguments `use 1`")
}
