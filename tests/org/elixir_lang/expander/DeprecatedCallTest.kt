package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageLevel

/**
 * The warnings `elixir_dispatch:check_deprecated/6` gives a call of a definition a module of the same file marks
 * `@deprecated`, which Elixir reads from the module only once it has compiled it. A warning is shown as the line of
 * the call it is for.
 */
class DeprecatedCallTest : ExpanderTestCase() {
    override val kernel = KernelImports(
        AttributeFixtures.KERNEL.functions,
        AttributeFixtures.KERNEL.macros + NameArity("in", 2) + NameArity("use", 1),
    )

    override val exports = Exports { module ->
        if (module == "Elixir.Kernel") {
            ModuleExports.Present(kernel.functions, kernel.macros, hasInfo = true)
        } else {
            AttributeFixtures.EXPORTS.of(module)
        }
    }

    fun testAFunctionCalledInALaterModuleBodyWarns() =
        assertEvery(DEP + "defmodule User do\n  Dep.old()\nend", "Dep: none\nUser: ${old(8)}")

    /** Elixir loads a module once it has compiled it, so its own body can't read its deprecations. */
    fun testAModuleBodyCallingItselfIsSilent() =
        assertEvery("defmodule Dep do\n  @deprecated \"use new\"\n  def old, do: 1\n  Dep.old()\nend", "Dep: none")

    /** The runtime group pass reports a function called in a function. */
    fun testAFunctionCalledInAFunctionIsSilent() =
        assertEvery(DEP + "defmodule User do\n  def f, do: Dep.old()\nend", "Dep: none\nUser: none")

    fun testAMacroCalledInAModuleBodyWarns() =
        assertEvery(DEP + "defmodule User do\n  require Dep\n  Dep.mac()\nend", "Dep: none\nUser: ${mac(9)}")

    /** A macro runs at compile time wherever it is called, which the runtime pass can't see; before 1.12.2 it wasn't checked. */
    fun testAMacroCalledInAFunctionWarnsFrom1_12_2() =
        assertSplit(
            DEP + "defmodule User do\n  require Dep\n  def f, do: Dep.mac()\nend",
            "1.12.2",
            "Dep: none\nUser: none",
            "Dep: none\nUser: ${mac(9)}",
        )

    fun testANestedModuleIsLoadedForALaterSiblingNotItsEnclosingBody() =
        assertEvery(
            """
            defmodule Outer do
              defmodule DepF do
                @deprecated "use new"
                def old, do: 1
              end
              DepF.old()
              defmodule Sibling do
                Outer.DepF.old()
              end
            end
            """.trimIndent(),
            "Outer: none\nOuter.DepF: none\nOuter.Sibling: deprecated Outer.DepF.old/0 \"use new\" line 8",
        )

    /** An imported function is checked on every release, through the remote call it was re-expanded as before 1.18. */
    fun testAnImportedFunctionWarns() =
        assertEvery(DEP + "defmodule User do\n  import Dep\n  old()\nend", "Dep: none\nUser: ${old(9)}")

    fun testAnImportedMacroWarns() =
        assertEvery(DEP + "defmodule User do\n  import Dep\n  mac()\nend", "Dep: none\nUser: ${mac(9)}")

    fun testACaptureOfARemoteFunctionWarns() =
        assertEvery(DEP + "defmodule User do\n  &Dep.old/0\nend", "Dep: none\nUser: ${old(8)}")

    fun testACaptureOfAnImportedFunctionWarns() =
        assertEvery(DEP + "defmodule User do\n  import Dep\n  &old/0\nend", "Dep: none\nUser: ${old(9)}")

    /** `use` calls the module's `__using__/1` macro, which has its own wording. */
    fun testUseOfADeprecatedUsingMacroWarns() =
        assertEvery(
            "defmodule Dep do\n  @deprecated \"gone\"\n  defmacro __using__(_), do: 1\nend\n" +
                "defmodule User do\n  use Dep\nend",
            "Dep: none\nUser: deprecated Dep.__using__/1 \"gone\" line 6",
        )

    /** A definition with defaults is deprecated at each arity the defaults make, and a call warns with its own arity. */
    fun testEachArityOfADefinitionWithDefaultsWarns() =
        assertEvery(
            "defmodule Dep do\n  @deprecated \"d\"\n  def old(a, b \\\\ 1), do: a\nend\n" +
                "defmodule User do\n  Dep.old(1)\n  Dep.old(1, 2)\nend",
            "Dep: none\nUser: deprecated Dep.old/1 \"d\" line 6; deprecated Dep.old/2 \"d\" line 7",
        )

    /** The check comes before the arguments are expanded. */
    fun testACallWarnsBeforeItsArguments() =
        assertEvery(
            "defmodule Dep do\n  @deprecated \"d\"\n  def old(a), do: a\nend\n" +
                "defmodule User do\n  Dep.old(\n    Dep.old(1)\n  )\nend",
            "Dep: none\nUser: deprecated Dep.old/1 \"d\" line 6; deprecated Dep.old/1 \"d\" line 7",
        )

    fun testADefinitionNotMarkedIsSilent() =
        assertEvery(
            "defmodule Dep do\n  def old, do: 1\nend\ndefmodule User do\n  Dep.old()\nend",
            "Dep: none\nUser: none",
        )

    fun testApplicationEnvInAModuleBodyWarnsFrom1_14() =
        assertSplit(
            "defmodule User do\n  Application.get_env(:a, :b)\nend",
            "1.14.0-rc.0",
            "User: none",
            "User: compile_env get_env/2 line 2",
        )

    fun testApplicationEnvInAFunctionIsSilent() =
        assertEvery("defmodule User do\n  def f, do: Application.get_env(:a, :b)\nend", "User: none")

    /** `erlang` has no `__info__/1` to read, so no release checks it. */
    fun testAnErlangModuleIsNotChecked() =
        assertEvery(
            "defmodule :erlang do\n  @deprecated \"d\"\n  def old, do: 1\nend\ndefmodule User do\n  :erlang.old()\nend",
            "erlang: none\nUser: none",
        )

    /** `check_deprecated/6` skips the modules that define `def` and `defmodule` themselves from 1.12.2. */
    fun testAnElixirModuleModuleIsNotCheckedFrom1_12_2() =
        assertSplit(
            "defmodule :elixir_module do\n  @deprecated \"d\"\n  def old, do: 1\nend\n" +
                "defmodule User do\n  :elixir_module.old()\nend",
            "1.12.2",
            "elixir_module: none\nUser: deprecated elixir_module.old/0 \"d\" line 6",
            "elixir_module: none\nUser: none",
        )

    /** `check_deprecated/6` skips `elixir_def` as it does `elixir_module`. */
    fun testAnElixirDefModuleIsNotCheckedFrom1_12_2() =
        assertSplit(
            "defmodule :elixir_def do\n  @deprecated \"d\"\n  def old, do: 1\nend\n" +
                "defmodule User do\n  :elixir_def.old()\nend",
            "1.12.2",
            "elixir_def: none\nUser: deprecated elixir_def.old/0 \"d\" line 6",
            "elixir_def: none\nUser: none",
        )

    /** `Kernel` has no deprecation to read: `check_deprecated/6` skips it on every release. */
    fun testKernelIsNotChecked() =
        assertEvery(
            "defmodule Kernel do\n  @deprecated \"d\"\n  def old, do: 1\nend\ndefmodule User do\n  Kernel.old()\nend",
            "Kernel: none\nUser: none",
        )

    /** `Macro.expand/2` checked a remote function it expanded once, before `Macro.Env` took the macro checks over in 1.17. */
    fun testMacroExpandChecksARemoteFunctionBefore1_17() =
        assertSplit(
            "defmodule Dep do\n  @deprecated \"d\"\n  def list, do: [1]\nend\n" +
                "defmodule User do\n  1 in Dep.list()\nend",
            "1.17.0-rc.0",
            "Dep: none\nUser: deprecated Dep.list/0 \"d\" line 6; deprecated Dep.list/0 \"d\" line 6",
            "Dep: none\nUser: deprecated Dep.list/0 \"d\" line 6",
        )

    override fun expandAndRender(code: String, version: String): String {
        val level = ElixirLanguageLevel.of(version)
        val file = Expander.expandFile(lower(code, level), Env.empty(level, kernel), level, exports, structs)

        return file.modules.flatMap(::lines).joinToString("\n")
    }

    private fun lines(result: ExpansionResult): List<String> {
        val warnings = result.warnings.joinToString("; ", transform = ::render).ifEmpty { "none" }

        return listOf("${result.module.removePrefix("Elixir.")}: $warnings") + result.nested.flatMap(::lines)
    }

    private fun render(warning: Warning): String {
        val line = lineOf(warning.at.meta)

        return when (warning) {
            is Warning.Deprecated ->
                "deprecated ${warning.module.removePrefix("Elixir.")}.${warning.name}/${warning.arity} " +
                    "\"${warning.reason}\" line $line"
            is Warning.CompileEnv -> "compile_env ${warning.name}/${warning.arity} line $line"
        }
    }

    private companion object {
        /** [DEP] is six lines, so the user module's first statement is on line 8. */
        const val DEP =
            "defmodule Dep do\n  @deprecated \"use new\"\n  def old, do: 1\n  @deprecated \"use new_m\"\n" +
                "  defmacro mac, do: 1\nend\n"

        fun old(line: Int) = "deprecated Dep.old/0 \"use new\" line $line"

        fun mac(line: Int) = "deprecated Dep.mac/0 \"use new_m\" line $line"
    }
}
