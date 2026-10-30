package org.elixir_lang.expander

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiFileFactory
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.language_level.ElixirLanguageFeature.CONFIG_READ_CONFIG
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.Lowering
import org.elixir_lang.psi.ElixirFile

/**
 * `Config`'s calls run as `Config.Reader.read_imports!/2` runs them. Each expected raise and value is what Elixir gave
 * for the same file's read on 1.20.4 and 1.11.4; outside a read, where Elixir raises, each declines, as a config file's
 * own expansion isn't Mix's read.
 */
class ConfigNativesTest : PlatformTestCase() {
    private lateinit var sdk: ModuleClauses.Lookup

    override fun setUp() {
        super.setUp()
        sdk = sdkClauses(project, testRootDisposable)
    }

    fun testAConfigOfABadAppOrOptionsRaisesAFunctionClauseError() {
        assertEquals("error Elixir.FunctionClauseError", top("Config.config(\"app\", [])"))
        assertEquals("error Elixir.FunctionClauseError", top("Config.config(:app, 1)"))
    }

    fun testAConfigOfANonKeywordRaisesAnArgumentError() {
        assertEquals("error Elixir.ArgumentError", top("Config.config(:app, [1])"))
    }

    fun testAConfigOfABadAppOrKeyRaisesAFunctionClauseError() {
        assertEquals("error Elixir.FunctionClauseError", top("Config.config(:app, \"k\", 1)"))
        assertEquals("error Elixir.FunctionClauseError", top("Config.config(\"app\", :k, 1)"))
    }

    fun testAConfigGivesTheConfigBefore() {
        assertEquals(
            "[{app,[{k,1},{j,2}]},{other,[{seen,[{app,[{k,1}]}]}]}]",
            config("import Config\n\nconfig :app, k: 1\nx = config :app, j: 2\nconfig :other, seen: x\n"),
        )
        assertEquals("[{app,[{k,1}]},{other,[{seen,[]}]}]", config("import Config\n\nx = config :app, k: 1\nconfig :other, seen: x\n"))
    }

    fun testALaterValueReplacesAnEarlierUnlessBothAreKeywords() {
        assertEquals(
            "[{app,[{nested,[{b,1},{a,[{x,1},{y,3}]}]},{list,[3]},{scalar,4}]}]",
            config(
                "import Config\n\nconfig :app, nested: [a: [x: 1, y: 2], b: 1], list: [1, 2], scalar: [a: 1]\n" +
                    "config :app, nested: [a: [y: 3]], list: [3], scalar: 4\n",
            ),
        )
    }

    fun testTheEnvAndTargetAreTheReads() {
        assertEquals("[{app,[{e,test},{t,host}]}]", config("import Config\n\nconfig :app, e: config_env(), t: config_target()\n", ConfigRead("test", "host")))
    }

    fun testReadConfigByLeg() {
        val code = "import Config\n\nconfig :app, k: 1\nconfig :app, j: read_config(:app)[:k], u: read_config(:nope)\n"

        if (CONFIG_READ_CONFIG.isSufficient(legLevel())) {
            assertEquals("[{app,[{k,1},{j,1},{u,nil}]}]", config(code))
        } else {
            assertEquals("error undefined_function", top(code))
        }
    }

    fun testReadConfigOfANonAtomByLeg() {
        val code = "Config.read_config(\"app\")"

        assertEquals(if (CONFIG_READ_CONFIG.isSufficient(legLevel())) "error Elixir.FunctionClauseError" else "error Elixir.UndefinedFunctionError", top(code))
    }

    /** A merge reads no state, so it merges outside a read too. */
    fun testAMergeOutsideAReadMerges() {
        assertEquals(
            "compiled [equal/0]",
            body("if Config.__merge__([a: [x: 1]], [a: [y: 2]]) == [a: [x: 1, y: 2]], do: def(equal, do: 1)\nif Config.__merge__([a: [x: 1]], [a: [y: 2]]) != [a: [x: 1, y: 2]], do: def(different, do: 1)"),
        )
    }

    fun testAMergeOfANonListRaisesAFunctionClauseError() {
        assertEquals("error Elixir.FunctionClauseError", top("Config.__merge__(1, [])", null))
    }

    fun testAMergeOfAnAppsNonListRaisesAFunctionClauseError() {
        assertEquals("error Elixir.FunctionClauseError", top("Config.__merge__([a: 1], [a: 2])", null))
        assertEquals("error Elixir.FunctionClauseError", top("Config.__merge__([a: []], [a: 1])", null))
    }

    fun testAMergeOfANonKeywordRaisesAnArgumentError() {
        assertEquals("error Elixir.ArgumentError", top("Config.__merge__([1], [])", null))
        assertEquals("error Elixir.ArgumentError", top("Config.__merge__([], [1])", null))
        assertEquals("error Elixir.ArgumentError", top("Config.__merge__([], [{\"a\", 1}])", null))
        assertEquals("error Elixir.ArgumentError", top("Config.__merge__([a: [x: 1]], [a: [1]])", null))
    }

    fun testAnImportOfANonBinaryRaisesAFunctionClauseError() {
        assertEquals("error Elixir.FunctionClauseError", top("Config.__import__!(:nope)"))
    }

    /** Elixir raises each of these outside a read, which a config file's own expansion is. */
    fun testEachCallOutsideAReadDeclines() {
        for (call in listOf(
            "Config.__env__!()",
            "Config.__target__!()",
            "Config.config(:app, :k, 1)",
            "Config.config(:app, k: 1)",
            "Config.__import__!(\"/nope.exs\")",
            "Mix.Config.__import__!(\"/nope.exs\", \"/\")",
        )) {
            assertEquals(call, "both", declines(call))
        }
    }

    /** Before 1.18 there is no such function, which raises as any undefined one does. */
    fun testReadConfigOutsideAReadByLeg() {
        val call = "Config.read_config(:app)"

        assertEquals(if (CONFIG_READ_CONFIG.isSufficient(legLevel())) "both" else "compiled [other/0]", declines(call))
    }

    private fun run(code: String, read: ConfigRead?, level: ElixirLanguageLevel = legLevel()): FileExpansion {
        val file = ReadAction.computeBlocking<ElixirFile, Throwable> {
            PsiFileFactory.getInstance(project).createFileFromText("config.exs", ElixirLanguage, code) as ElixirFile
        }
        val forms = ReadAction.computeBlocking<_, Throwable> { Lowering.lower(file, level) }

        return Expander.expandFile(
            forms,
            Env.empty(level, legKernel),
            level,
            legExports,
            legStructs,
            NO_APP_ENV,
            clauses = sdk,
            config = read,
        )
    }

    /** How [code]'s file ended in [read]. */
    private fun top(code: String, read: ConfigRead? = ConfigRead("dev", "host")): String =
        when (val top = run(code, read).top) {
            is Expansion.Expanded -> "expanded"
            is Expansion.Error -> "error ${top.kind}"
            is Expansion.Unported -> "unported"
            is Expansion.Opaque -> "opaque"
        }

    /** The config [code] leaves in [read]. */
    private fun config(code: String, read: ConfigRead = ConfigRead("dev", "host")): String {
        assertEquals("expanded", top(code, read))

        return read.config.toString()
    }

    /** How a module with [statements] as its body ended outside a read, with what it defined. */
    private fun body(statements: String): String {
        val expansion = run("defmodule A do\n$statements\nend\n", null)
        val result = expansion.modules.single()

        return "${result.ended::class.simpleName!!.lowercase()} ${result.table.entries.keys.map { "${it.name}/${it.arity}" }.sorted()}"
    }

    /** Whether a module's definitions under a guard on [call], outside a read, are `both` left defined: what declining gives. */
    private fun declines(call: String): String {
        val rendered = body(
            "x = try do\n  $call\n  :ran\nrescue\n  _ -> :raised\nend\n" +
                "if x == :ran, do: def(ran, do: 1)\nif x != :ran, do: def(other, do: 1)",
        )

        return if (rendered.endsWith("[other/0, ran/0]") || rendered.startsWith("stopped")) "both" else rendered
    }
}
