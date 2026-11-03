package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiCompiledFile
import com.intellij.psi.PsiManager
import junit.framework.TestCase.assertEquals
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.onAStack
import org.elixir_lang.psi.ElixirFile
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Each `.beam` in every app of the leg's Elixir and OTP, by module as atom text. */
internal val appBeams: Map<String, File> by lazy {
    (appEbins("ELIXIR_LANG_ELIXIR_PATH") + appEbins("ERLANG_SDK_HOME"))
        .flatMap { ebin -> ebin.listFiles { file -> file.extension == "beam" }.orEmpty().toList() }
        .associateBy { it.nameWithoutExtension }
}

/**
 * The leg's modules' clauses, read from their [appBeams] `.beam`s in [project] as `ProjectModules` reads an SDK's, each
 * once. The `.beam`s' roots are readable until [disposable] is disposed.
 */
internal fun sdkClauses(project: Project, disposable: Disposable): ModuleClauses.Lookup {
    appBeams.values.map { it.parentFile.parentFile.parentFile.path }.distinct().forEach {
        VfsRootAccess.allowRootAccess(disposable, it)
    }

    val read = HashMap<String, ModuleClauses?>()

    return ModuleClauses.Lookup { module -> read.getOrPut(module) { appBeams[module]?.let { clausesOf(project, it) } } }
}

/** The clauses of [beam], read through its decompiled file in [project]; `null` where it has no export chunk. */
internal fun clausesOf(project: Project, beam: File): ModuleClauses? =
    ReadAction.computeBlocking<ModuleClauses?, Throwable> {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(beam)!!
        val compiled = PsiManager.getInstance(project).findFile(file) as PsiCompiledFile
        val mirror = compiled.decompiledPsiFile as ElixirFile
        val bytes = beam.readBytes()
        val deprecations = (LoadedModules.exports(bytes, beam.path) as? ModuleExports.Present)?.deprecated

        deprecations
            ?.let { CompiledModule.of(bytes, beam.path, mirror, legLevel(), it) }
            ?.let { ModuleClauses.of(it, file) }
    }

/**
 * What [answer], an expression of the loaded module `m`, gives for each of [modules] on the leg's node, [BATCH_SIZE]
 * to a compile. A module its release doesn't hold is used from its [appBeams] `.beam` for the probe, through
 * [USE_MODULE]. A module the node can't load is null.
 */
internal fun ProbeHarness.probeLoaded(modules: List<String>, answer: String): Map<String, OtpErlangObject?> =
    modules.chunked(BATCH_SIZE).flatMap { batch ->
        // Names are binaries made atoms at run time: a quoted atom per module makes the compiler warn once for each.
        val list = batch.joinToString(", ") { "{\"$it\", ${elixirString(appBeams.getValue(it).path)}}" }
        val compiled = compileSource(
            USE_MODULE + DROP_MODULE + """
            for {name, path} <- [$list] do
              m = String.to_atom(name)

              if use_module.(m, path) do
                IntellijElixir.Quoter.Probe.send(__ENV__, {m, {:loaded, ($answer)}})
                drop_module.(m)
              else
                IntellijElixir.Quoter.Probe.send(__ENV__, {m, :unloaded})
              end
            end
            """.trimIndent(),
        )

        assertEquals("probe compile status", OtpErlangAtom("ok"), compiled.status)

        compiled.messages.map { message ->
            val (module, value) = (message as OtpErlangTuple).elements()

            (module as OtpErlangAtom).atomValue() to (value as? OtpErlangTuple)?.elementAt(1)
        }
    }.toMap()

/** [legExports], and where it has none, the module's [appBeams] `.beam`'s, as for `EEx`, each read once. */
internal val appExports: Exports = ConcurrentHashMap<String, ModuleExports>().let { read ->
    Exports { module ->
        legExports.of(module).takeUnless { it == ModuleExports.Absent }
            ?: read.computeIfAbsent(module) { appBeams[module]?.let { LoadedModules.exports(it.readBytes(), it.path) } ?: ModuleExports.Absent }
    }
}

/** The one module of [forms], expanded from `/s.ex` at [level] with [clauses] on a thread of [stackSize] bytes. */
internal fun expandedOnAStack(forms: ElixirAst, level: ElixirLanguageLevel, clauses: ModuleClauses.Lookup, stackSize: Long): ExpansionResult =
    onAStack(stackSize) {
        Expander.expandFile(forms, Env.empty(level, legKernel), level, appExports, legStructs, legAppEnv(), clauses = clauses, file = { "/s.ex" })
            .modules
            .single()
    }!!.getOrThrow()

/**
 * [block], with the leg's `EEx` and `Mix.Generator`, which the quoter's release doesn't hold, used from their
 * [appBeams] `.beam`s for Elixir's compiles, through [USE_MODULE].
 */
internal fun <T> ProbeHarness.withEex(block: () -> T): T {
    val ebin = appBeams.getValue("Elixir.EEx").parentFile
    val beams = (appBeams.filterValues { it.parentFile == ebin } + ("Elixir.Mix.Generator" to appBeams.getValue("Elixir.Mix.Generator")))
        .entries
        .joinToString(", ") { (module, beam) -> "{\"$module\", ${elixirString(beam.path)}}" }

    val loaded = compileSource(USE_MODULE + "for {name, path} <- [$beams], do: true = use_module.(String.to_atom(name), path)")

    assertEquals("EEx's load", OtpErlangAtom("ok"), loaded.status)
    try {
        return block()
    } finally {
        compileSource(DROP_MODULE + "for {name, _} <- [$beams], do: drop_module.(String.to_atom(name))")
    }
}

/**
 * Binds `use_module.(m, path)`, true where `m` is loaded. Test forks share the node, and a second load would make a
 * running user's copy old, which a purge then kills, so a module loaded from `path` is loaded once and its uses counted.
 */
internal val USE_MODULE = """
    use_module = fn m, path ->
      key = {IntellijElixir.Test.SharedLoads, m}

      :global.trans({IntellijElixir.Test.SharedLoads, self()}, fn ->
        users = :persistent_term.get(key, 0)

        cond do
          users > 0 ->
            :persistent_term.put(key, users + 1)
            true

          Code.ensure_loaded?(m) ->
            true

          match?({:module, _}, :code.load_binary(m, String.to_charlist(path), File.read!(path))) ->
            :persistent_term.put(key, 1)
            true

          true ->
            false
        end
      end, [node()])
    end
"""

/** Binds `drop_module.(m)`, which ends a use of `m` that [USE_MODULE] counted, unloading `m` after the last. */
private val DROP_MODULE = """
    drop_module = fn m ->
      key = {IntellijElixir.Test.SharedLoads, m}

      :global.trans({IntellijElixir.Test.SharedLoads, self()}, fn ->
        case :persistent_term.get(key, 0) do
          0 ->
            :ok

          1 ->
            :persistent_term.erase(key)
            :code.delete(m)
            :code.purge(m)

          users ->
            :persistent_term.put(key, users - 1)
        end
      end, [node()])
    end
"""

/** [text] as an Elixir string literal, so a Windows path's backslashes aren't read as escapes. */
internal fun elixirString(text: String): String =
    "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("#", "\\#") + "\""

/** Modules per probe compile: 1,768 took about 30 s on the slowest leg, so a batch takes about a tenth of that. */
private const val BATCH_SIZE = 200
