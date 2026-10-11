package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import org.elixir_lang.NameArity
import java.io.File

/**
 * What [LoadedModules] reads from each `.beam` in every app of the leg's Elixir and OTP, against what Elixir gives once it
 * loads the module: `__info__(:functions)` and `(:macros)` for a module that has `__info__/1`, `module_info(:exports)`
 * for one that doesn't. A module the node can't load is left out, and the failure lists them.
 */
class LoadedModulesTest : ProbeTestCase() {
    /** Each module's `.beam`, by module as atom text. */
    private val beams: Map<String, File> by lazy {
        (appEbins("ELIXIR_LANG_ELIXIR_PATH") + appEbins("ERLANG_SDK_HOME"))
            .flatMap { ebin -> ebin.listFiles { file -> file.extension == "beam" }.orEmpty().toList() }
            .associateBy { it.nameWithoutExtension }
    }

    fun testEveryModuleExportsWhatElixirLoads() {
        val (read, probed, unloaded) = compared()

        assertEquals("modules compared (the node loads no $unloaded)", emptyList<String>(), mismatches(read, probed))
        assertTrue(
            "every Elixir app's and OTP's modules are compared: ${read.size}",
            listOf("Elixir.Kernel", "Elixir.EEx", "Elixir.ExUnit", "Elixir.IEx", "Elixir.Logger", "Elixir.Mix", "lists", "ssl")
                .all { it in read },
        )
    }

    fun testEveryModuleIsReadable() {
        assertEquals(
            "unreadable",
            emptyList<String>(),
            beams.keys.sorted().filter { read(it) !is ModuleExports.Present },
        )
    }

    fun testALostExportIsAMismatch() {
        val (read, probed) = compared()
        val module = read.entries.first { it.value.functions.size > 1 }
        val lost = read + (module.key to module.value.copy(functions = module.value.functions.dropLast(1)))

        assertEquals(listOf(module.key), mismatches(lost, probed).map { it.substringBefore(":") })
    }

    /** What Elixir gives a loaded module: with [hasInfo], `__info__`'s answers; without, `module_info(:exports)`. */
    private data class Loaded(val functions: List<NameArity>, val macros: List<NameArity>, val hasInfo: Boolean)

    private data class Compared(val read: Map<String, Loaded>, val probed: Map<String, Loaded>, val unloaded: Set<String>)

    private fun compared(): Compared {
        val read = beams.keys.sorted().mapNotNull { module ->
            (read(module) as? ModuleExports.Present)?.let {
                module to Loaded(it.functions.sorted(), it.macros.sorted(), it.hasInfo)
            }
        }.toMap()
        val (probed, unloaded) = probed(read.keys.toList())

        return Compared(read.filterKeys { it !in unloaded }, probed, unloaded)
    }

    /**
     * What the node gives for each of [modules]. One its release doesn't hold is loaded from its `.beam` for the probe,
     * then unloaded.
     */
    private fun probed(modules: List<String>): Pair<Map<String, Loaded>, Set<String>> {
        // Names are binaries made atoms at run time: a quoted atom per module makes the compiler warn once for each.
        val list = modules.joinToString(", ") { "{\"$it\", ${elixirString(beams.getValue(it).path)}}" }
        val compiled = harness.compileSource(
            """
            for {name, path} <- [$list] do
              m = String.to_atom(name)
              held = Code.ensure_loaded?(m)
              loaded = held or match?({:module, _}, :code.load_binary(m, String.to_charlist(path), File.read!(path)))

              cond do
                not loaded -> IntellijElixir.Quoter.Probe.send(__ENV__, {m, :unloaded})
                function_exported?(m, :__info__, 1) ->
                  IntellijElixir.Quoter.Probe.send(__ENV__, {m, m.__info__(:functions), m.__info__(:macros)})
                true -> IntellijElixir.Quoter.Probe.send(__ENV__, {m, m.module_info(:exports)})
              end

              if loaded and not held do
                :code.delete(m)
                :code.purge(m)
              end
            end
            """.trimIndent(),
        )

        assertEquals("probe compile status", OtpErlangAtom("ok"), compiled.status)

        val answers = compiled.messages.map { (it as OtpErlangTuple).elements() }
        val unloaded = answers.filter { it[1] is OtpErlangAtom }.map { (it[0] as OtpErlangAtom).atomValue() }.toSet()
        val probed = answers.filter { it[1] is OtpErlangList }.associate { answer ->
            val module = (answer[0] as OtpErlangAtom).atomValue()

            module to if (answer.size == 3) {
                Loaded(nameArities(answer[1]), nameArities(answer[2]), hasInfo = true)
            } else {
                Loaded(nameArities(answer[1]), emptyList(), hasInfo = false)
            }
        }

        return probed to unloaded
    }

    /** [text] as an Elixir string literal, so a Windows path's backslashes aren't read as escapes. */
    private fun elixirString(text: String): String =
        "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("#", "\\#") + "\""

    private fun nameArities(list: OtpErlangObject): List<NameArity> =
        (list as OtpErlangList).elements().map {
            val (name, arity) = (it as OtpErlangTuple).elements()

            NameArity((name as OtpErlangAtom).atomValue(), (arity as OtpErlangLong).intValue())
        }.sorted()

    /** The modules whose [read] exports differ from the [probed] ones. */
    private fun mismatches(read: Map<String, Loaded>, probed: Map<String, Loaded>): List<String> =
        (read.keys + probed.keys).sorted().mapNotNull { module ->
            val ours = read[module]
            val theirs = probed[module]

            when {
                ours == theirs -> null
                ours == null -> "$module: only Elixir loads it"
                theirs == null -> "$module: only the leg read it"
                ours.hasInfo != theirs.hasInfo -> "$module: hasInfo ${ours.hasInfo}, Elixir ${theirs.hasInfo}"
                else -> "$module: functions lost ${theirs.functions - ours.functions.toSet()}, " +
                    "extra ${ours.functions - theirs.functions.toSet()}; " +
                    "macros lost ${theirs.macros - ours.macros.toSet()}, extra ${ours.macros - theirs.macros.toSet()}"
            }
        }

    private fun read(module: String): ModuleExports = beams.getValue(module).let { LoadedModules.exports(it.readBytes(), it.path) }

    private fun List<NameArity>.sorted() = sortedWith(compareBy({ it.name }, { it.arity }))
}
