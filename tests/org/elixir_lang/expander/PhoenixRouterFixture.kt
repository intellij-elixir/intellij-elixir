package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangMap
import com.ericsson.otp.erlang.OtpErlangObject
import com.intellij.openapi.util.io.FileUtil
import org.elixir_lang.elixir_surface.LegManifest
import java.io.File

/**
 * Phoenix projects compiled by Mix, and Elixir's oracle's answer for each of their routers.
 *
 * [demo] is [FIXTURE], a generated Phoenix project, with `routers/`, which Mix doesn't compile; its lock resolved under
 * the one Elixir its `elixir-version` names, so it is compiled on that leg alone, once for the JVM.
 */
internal object PhoenixRouterFixture {
    val FIXTURE: File = File("testData/org/elixir_lang/expander/phoenix_router").absoluteFile
    val SHAPES: File = File(FIXTURE, "shapes")

    /** A project compiled with its dependencies, and Elixir's oracle's map of its routers. */
    class Compiled(val project: File, val routers: OtpErlangMap)

    /** The generated project, `null` on a leg other than its lock's, kept with a failure for each test that asks. */
    val demo: Result<Compiled?> by lazy {
        runCatching {
            if (File(FIXTURE, "elixir-version").readText().trim() != LegManifest.environment("ELIXIR_VERSION")) return@runCatching null

            compile("phoenix-router-fixture", "phoenixRouterFixture") { project -> FileUtil.copyDir(FIXTURE, project) { it != SHAPES } }
        }
    }

    /** A Mix project [lay] writes, with the dependencies Gradle's `test` names by [properties], compiled, and the oracle's map of its routers. */
    fun compile(name: String, properties: String, lay: (File) -> Unit): Compiled {
        val fixture = MixFixture(name, properties, "phoenix")

        lay(fixture.project)
        fixture.compile()
        fixture.runOracle("PHOENIX_ROUTER_ORACLE")

        return Compiled(fixture.project, etf(File(fixture.oracle, "routers.etf")) as OtpErlangMap)
    }

    /** Elixir's entry for [router] in [routers]. */
    fun entry(routers: OtpErlangMap, router: String): OtpErlangMap =
        routers.get(OtpErlangBinary("Elixir.$router".toByteArray())) as? OtpErlangMap ?: error("Elixir compiled no router $router")

    fun field(routers: OtpErlangMap, router: String, key: String): OtpErlangObject = entry(routers, router).get(OtpErlangAtom(key))

    /** Elixir's `__routes__/0` of [router], each route's verb, path, plug and options. */
    fun routesOf(routers: OtpErlangMap, router: String): List<OtpErlangObject> = (field(routers, router, "routes") as OtpErlangList).elements().toList()
}
