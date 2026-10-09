package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageLevel

/**
 * `defimpl` where the expander ends it before the implementation's module is compiled: the options and the protocol it
 * can't read, and the errors the options and the protocol raise, each at the releases that differ.
 */
class ImplMacroTest : ExpanderTestCase() {
    override val kernel = KernelImports(
        functions = emptyList(),
        macros = listOf(NameArity("defimpl", 2), NameArity("defimpl", 3), NameArity("defmodule", 2), NameArity("def", 2)),
    )

    override val exports = Exports { module ->
        when (module) {
            "Elixir.Kernel" -> ModuleExports.Present(kernel.functions, kernel.macros, hasInfo = true)
            "Elixir.Unreadable" -> ModuleExports.Unreadable
            "Elixir.Plain" -> ModuleExports.Present(listOf(NameArity("f", 1)), emptyList(), hasInfo = true)
            "Elixir.Proto" ->
                ModuleExports.Present(listOf(NameArity("__protocol__", 1), NameArity("f", 1)), emptyList(), hasInfo = true)
            else -> ModuleExports.Absent
        }
    }

    /** `Kernel.defimpl/3` puts `for:` in the options, and fails outside a module without it: from v1.12. */
    fun testAnImplementationOutsideAModuleNeedsFor() {
        val code = "defimpl P do\nend"

        assertSplit(code, "1.12.0-rc.0", "unported `$code`", "error impl_outside_no_for `$code`")
    }

    /** Up to v1.13 `for: nil` raises as well, though `for:` is written. */
    fun testANilForRaisesUpToV113() {
        val code = "defimpl P, for: nil do\nend"

        assertLevels(code, LEVELS.filter { isBefore(it, "1.14.0-rc.0") && !isBefore(it, "1.12.0-rc.0") }) {
            "error impl_outside_no_for `$code`"
        }
    }

    /** `DEFIMPL_IMPL4`: `Protocol.__impl__/4` takes the options as they are; before, exactly `do:` and `for:`. */
    fun testTheOptionsAnImplementationTakesChangeAtV114() {
        val code = "defimpl P, for: Integer, foo: 1 do\nend"

        assertSplit(code, "1.14.0-rc.0", "error impl_options `$code`", "error impl_bad_opt `$code`")
    }

    fun testAnImplementationWithoutDoRaisesFromV114() {
        val code = "defimpl P, for: Integer"

        assertSplit(code, "1.14.0-rc.0", "error impl_options `$code`", "error impl_no_do `$code`")
    }

    fun testOptionsThatAreNotAKeywordListAreNotFollowed() {
        val code = "defimpl P, opts"

        assertEvery(code, "unported `$code`")
    }

    /** `Exports` can't read the protocol, so whether it is one isn't known. */
    fun testAnUnreadableProtocolStopsTheModule() {
        val code = "defmodule A do\n  defimpl Unreadable, for: Integer do\n  end\nend"

        assertEvery(code, "module Elixir.A stopped `defimpl Unreadable, for: Integer do\n  end`")
    }

    fun testAModuleThatIsNotAProtocolRaises() {
        val code = "defmodule A do\n  defimpl Plain, for: Integer do\n  end\nend"

        assertEvery(code, "module Elixir.A raised impl_not_a_protocol `defimpl Plain, for: Integer do\n  end`")
    }

    fun testAModuleThatIsNotThereRaises() {
        val code = "defmodule A do\n  defimpl Missing, for: Integer do\n  end\nend"

        assertEvery(code, "module Elixir.A raised impl_not_available `defimpl Missing, for: Integer do\n  end`")
    }

    /**
     * Elixir checks the protocol of a top-level `defimpl` when the file runs, after the modules defined before it, the
     * nested ones among them, so a protocol one of them defines isn't refused when the call is expanded.
     */
    fun testATopLevelImplementationOfAProtocolANestedModuleDefinesIsNotRefused() {
        val code = "defmodule A do\n  defmodule P do\n    def __protocol__(x), do: x\n  end\nend\n" +
            "defimpl A.P, for: Integer do\nend"

        assertEvery(code, "module Elixir.A compiled")
    }

    /** What the earlier modules define decides the check, so one that raises isn't reported before they are compiled. */
    fun testATopLevelImplementationAfterAModuleIsNotRefused() {
        val code = "defmodule B do\nend\ndefimpl NoSuch, for: Integer do\nend"

        assertEvery(code, "module Elixir.B compiled")
    }

    /**
     * The file is expanded whole before any of it runs, so a form after the call that fails to expand is reported first,
     * and code before it that runs can define the protocol: a check that fails is not answered at top level.
     */
    fun testATopLevelImplementationOfAMissingProtocolIsNotRefused() {
        val code = "defimpl NoSuch, for: Integer do\nend"

        assertEvery(code, "unported `$code`")
    }

    fun testATopLevelImplementationBeforeAFormThatCannotExpandIsNotRefused() {
        val code = "defimpl NoSuch, for: Integer do\nend\nfoo()"

        assertEvery(code, "unported `defimpl NoSuch, for: Integer do\nend`")
    }

    /**
     * `for:` is expanded in the env of `defimpl/3`, where `__MODULE__` is `Kernel`, by `Macro.expand_literals/2` in
     * v1.14.1 alone. v1.14.0's `Macro.expand_literal/2` leaves `__MODULE__` to the body, which names the enclosing
     * module, as the env of `__impl__/1` does from v1.14.2.
     */
    fun testTheTypeIsExpandedInTheEnvOfDefimplOnlyInV1141() {
        val versions = listOf("1.14.0-rc.0", "1.14.0", "1.14.1", "1.14.2", "1.14.5")

        listOf(
            "__MODULE__" to ("Elixir.Proto.Kernel" to "Elixir.Proto.A"),
            "__MODULE__.Foo" to ("Elixir.Proto.Kernel.Foo" to "Elixir.Proto.A.Foo"),
        ).forEach { (type, names) ->
            val code = "defmodule A do\n  defimpl Proto, for: $type do\n  end\nend"

            assertEquals(
                versions.joinToString("\n") { "$it: ${if (it == "1.14.1") names.first else names.second}" },
                versions.joinToString("\n") { "$it: ${implementations(code, it).joinToString()}" },
            )
        }
    }

    /** The modules `defimpl` of `Proto` defined in [code], outermost first. */
    private fun implementations(code: String, version: String): List<String> {
        val level = ElixirLanguageLevel.of(version)
        val result = Expander.expandFile(lower(code, level), Env.empty(level, kernel), level, exports, structs)

        fun modules(module: ExpansionResult): List<ExpansionResult> = listOf(module) + module.nested.flatMap(::modules)

        return result.modules.flatMap(::modules).map { it.module }.filter { it.startsWith("Elixir.Proto.") }
    }

    override fun expandAndRender(code: String, version: String): String {
        val level = ElixirLanguageLevel.of(version)
        val result = Expander.expandFile(lower(code, level), Env.empty(level, kernel), level, exports, structs)
        val modules = result.modules.map { ModuleRendering.render(code, it) }

        return if (modules.isEmpty()) render(code, result.top) else modules.joinToString("\n")
    }
}
