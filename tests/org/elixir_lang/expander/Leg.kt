package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import junit.framework.TestCase.assertEquals
import org.elixir_lang.beam.ReadResult
import org.elixir_lang.elixir_surface.LegManifest
import org.elixir_lang.intellij_elixir.Quoter
import org.elixir_lang.language_level.ElixirLanguageFeature.COMPILE_ENV_TAKES_ENV_FIRST
import org.elixir_lang.language_level.ElixirLanguageLevel
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/** The leg's Elixir, and its Erlang/OTP when the test JVM is told it: an empty or missing version leaves it unknown. */
internal fun legLevel(): ElixirLanguageLevel =
    ElixirLanguageLevel.of(LegManifest.environment("ELIXIR_VERSION"), System.getenv("ERLANG_VERSION"))

/** `compile_env/4`'s read of [path] in [app] under [env], else [default], in the leg's argument order. */
internal fun legCompileEnv(app: String, path: String, default: String, env: String = "__ENV__"): String =
    if (COMPILE_ENV_TAKES_ENV_FIRST.isSufficient(legLevel())) "Application.compile_env($env, $app, $path, $default)"
    else "Application.__compile_env__($app, $path, $default, $env)"

/** `compile_env!/3`'s read of [path] in [app] under [env], in the leg's argument order. */
internal fun legCompileEnvBang(app: String, path: String, env: String): String =
    if (COMPILE_ENV_TAKES_ENV_FIRST.isSufficient(legLevel())) "Application.compile_env!($env, $app, $path)"
    else "Application.__compile_env__!($app, $path, $env)"

/** Whether the leg's `Path.expand/2` reads the working directory only for a relative directory, as from 1.16.0-rc.1. */
internal fun legExpandsPathsLazily(): Boolean = legLevel().elixir >= ElixirLanguageLevel.of("1.16.0-rc.1").elixir

internal val legKernel: KernelImports by lazy {
    val beam = File(LegManifest.ebin(), "Elixir.Kernel.beam")
    val read = KernelImports.read(beam.readBytes(), beam.path)

    (read as? ReadResult.Present)?.value ?: throw AssertionError("$beam: $read")
}

/** The modules under each `lib/<app>/ebin` of the leg's Elixir and of `ERLANG_SDK_HOME`, as the SDK's class roots hold them. */
internal val legExports: Exports by lazy {
    val cache = ConcurrentHashMap<String, ModuleExports>()

    Exports { module ->
        cache.computeIfAbsent(module) { legBeam(module)?.let(::moduleExports) ?: ModuleExports.Absent }
    }
}

/** The structs of [legExports]' modules, from each beam's `Dbgi` chunk. */
internal val legStructs: Structs by lazy {
    val cache = ConcurrentHashMap<String, ModuleStruct>()

    Structs { module -> cache.computeIfAbsent(module, ::moduleStruct) }
}

/** No application's `.app` can be read. */
internal val NO_APP_ENV = AppEnv { _, _ -> AppEnvValue.Unreadable }

/**
 * The env a default build sees with [apps]' `.app` texts, by name, and the leg's apps' `.app` files, with [configured]
 * saying whether a config file sets an app's key, and [unread] whether one has a setting of the app that can't be read.
 */
internal fun legAppEnv(
    apps: Map<String, String> = emptyMap(),
    configured: (app: String, key: OtpErlangObject) -> Boolean = { _, _ -> false },
    unread: (app: String) -> Boolean = { false },
): AppEnv {
    val level = legLevel()
    val cache = ConcurrentHashMap<String, AppDeclaration>()

    return AppEnv.of(configured, unread) { app ->
        cache.computeIfAbsent(app) {
            val text = apps[app] ?: legEbins.map { File(it, "$app.app") }.firstOrNull(File::isFile)?.readText()

            text?.let { AppFile.read(it, level) }?.let(AppDeclaration::Read) ?: AppDeclaration.Unknown
        }
    }
}

private val legEbins: List<File> by lazy { appEbins("ELIXIR_LANG_ELIXIR_PATH") + appEbins("ERLANG_SDK_HOME") }

/** Each app's `ebin` under the `lib` of the install the leg's [variable] names. */
internal fun appEbins(variable: String): List<File> = appEbins(File(LegManifest.environment(variable), "lib"))

/** Each app's `ebin` in [lib], a directory of apps. */
internal fun appEbins(lib: File): List<File> = lib.listFiles { app -> app.isDirectory }.orEmpty().map { File(it, "ebin") }

internal fun legBeam(module: String): File? = legEbins.map { File(it, "$module.beam") }.firstOrNull(File::isFile)

private fun moduleStruct(module: String): ModuleStruct =
    LoadedModules.struct(legExports.of(module)) { legBeam(module)!!.let { it.readBytes() to it.path } }

private fun moduleExports(beam: File): ModuleExports = LoadedModules.exports(beam.readBytes(), beam.path)

/** Every module the leg's Elixir and OTP ebins hold, as atom text. */
internal fun legModules(): List<String> =
    legEbins.flatMap { ebin -> ebin.listFiles { file -> file.extension == "beam" }.orEmpty().map { it.nameWithoutExtension } }

/**
 * The leg's `ex_unit` loaded on the quoter node, as `mix test` has it: the quoter's release holds no `ex_unit`, and in
 * its embedded mode nothing on the code path loads until asked. Each module is a [USE_MODULE] use that is never dropped.
 */
internal fun exUnitOnQuoter() {
    val ebin = File(LegManifest.environment("ELIXIR_LANG_ELIXIR_PATH"), "lib/ex_unit/ebin")
    val compiled = Quoter.compile(
        USE_MODULE + """
        ebin = ${elixirString(ebin.invariantSeparatorsPath)}
        true = Code.prepend_path(ebin)

        for beam <- Path.wildcard(Path.join(ebin, "*.beam")) do
          true = use_module.(String.to_atom(Path.basename(beam, ".beam")), beam)
        end

        {:module, _} = Code.ensure_loaded(ExUnit.Case)
        """.trimIndent(),
        60.seconds,
    )

    assertEquals("ex_unit on the code path: ${compiled.failure}", OtpErlangAtom("ok"), compiled.status)
}

/** What a compile reported, with what it raised as text: the reply holds a raise's message as a binary. */
internal val Quoter.Compiled.failure: String
    get() {
        val raised = (status as? OtpErlangTuple)?.elements()?.map { (it as? OtpErlangBinary)?.binaryValue()?.let(::String) ?: it }

        return "$diagnostics $raised"
    }
