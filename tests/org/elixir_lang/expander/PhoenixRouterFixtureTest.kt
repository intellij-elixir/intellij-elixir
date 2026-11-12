package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangMap
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.util.io.FileUtil
import org.elixir_lang.coverage.ExpanderProgress
import org.elixir_lang.elixir_surface.LegManifest
import org.elixir_lang.junit.HeavyTestCase
import org.elixir_lang.lowering.inspect
import java.io.File

/**
 * Routers as Elixir's compile of a Mix project gives them: what `Phoenix.Router.Helpers.define/2` creates for a router
 * with helpers, read from another file, and how a router ends, with its routes.
 *
 * Two projects, each compiled once for the class. [FIXTURE] is a generated Phoenix project, its two
 * `Mix.Project.build_path()` lines cut from `config.exs`, with `routers/`, which Mix doesn't compile; its lock resolved
 * under the one Elixir its `elixir-version` names, and on any other leg its tests assert nothing. The shapes are
 * [SHAPES] and routers of many routes written here, compiled with the `Phoenix.Component` fixture's `mix.exs` and the
 * dependencies of its leg's lock, so on each leg that fixture has a lock for.
 */
class PhoenixRouterFixtureTest : HeavyTestCase() {
    private var corpus: PhoenixCorpus? = null

    override fun tearDown() {
        try {
            if (corpus != null) LegSdk.tearDown(module)
        } finally {
            corpus = null
            super.tearDown()
        }
    }

    /** Each router's `Helpers`, read from another file, exports the functions Elixir's does. */
    fun testARoutersHelpersExportWhatElixirsDo() {
        val compiled = shapes.getOrThrow() ?: return
        val corpus = setUp(compiled)
        val holder = ProjectModules(project)
        val asker = corpus.psi(compiled.project, "lib/asker.ex")
        val routers = compiled.routers
        val helpers = listOf(HELPERS_ROUTER, helpersOn(HELPERS_COMPILED))

        val expected = helpers.map { "$it.Helpers ${exported(field(routers, it, "helpers") as OtpErlangMap)}" }
        val actual = helpers.map { "$it.Helpers ${exported(exports(holder, asker, "Elixir.$it.Helpers"))}" }

        assertEquals(expected.joinToString("\n"), actual.joinToString("\n"))
    }

    /**
     * A router of many routes with helpers off compiles, with Elixir's functions and macros and its routes in
     * order, and one with helpers on stops: `define/2`'s `for {_helper, helper_routes} <- groups` reads a map of its
     * helpers in an order the expander doesn't know, and declines `the order of a map of more than 32 keys`.
     */
    fun testARouterOfManyRoutesEndsAsElixirsCompileDoes() {
        val compiled = shapes.getOrThrow() ?: return
        val corpus = setUp(compiled)
        val holder = ProjectModules(project)
        val routers = compiled.routers
        val off = helpersOff(MANY_ROUTES)
        val on = helpersOn(MANY_ROUTES)

        val expected = listOf(compiledByElixir(routers, off), "$on stopped Phoenix.Router.__before_compile__/1")
        val actual = listOf(off, on).map { ended(it, expansion(holder, corpus.psi(compiled.project, "lib/${fileName(it)}.ex")).modules.single()) }

        assertEquals(expected.joinToString("\n"), actual.joinToString("\n"))
    }

    /**
     * The generated project's router compiles with the routes Elixir's does, its dev routes among them, as
     * `config/dev.exs` sets `dev_routes`; and so does the copy of it without them.
     */
    fun testTheGeneratedRouterEndsAsElixirsCompileDoes() {
        val compiled = demo.getOrThrow() ?: return
        val corpus = setUp(compiled)
        val holder = ProjectModules(project)
        val routers = compiled.routers
        val files = mapOf(
            "DemoWeb.Router" to "lib/demo_web/router.ex",
            "DemoWeb.RouterWithoutDevRoutes" to "routers/router_without_dev_routes.ex",
        )

        val expected = files.keys.map { compiledByElixir(routers, it) }
        val actual = files.map { (router, path) -> ended(router, expansion(holder, corpus.psi(compiled.project, path)).modules.single()) }

        assertEquals(expected.joinToString("\n"), actual.joinToString("\n"))
    }

    private fun setUp(compiled: Compiled): PhoenixCorpus =
        ExpanderProgress.setUp(project, module, testRootDisposable, compiled.project).also { corpus = it }

    /** A project compiled with its dependencies, and Elixir's oracle's map of its routers. */
    private class Compiled(val project: File, val routers: OtpErlangMap)

    private companion object {
        val FIXTURE: File = File("testData/org/elixir_lang/expander/phoenix_router").absoluteFile
        val SHAPES: File = File(FIXTURE, "shapes")
        val COMPONENT_FIXTURE: File = File("testData/org/elixir_lang/expander/phoenix_component").absoluteFile

        const val HELPERS_ROUTER = "RouterFixture.HelpersRouter"

        /** Routes in a router with helpers on that the expander compiles: under 33, the map `define/2` reads. */
        const val HELPERS_COMPILED = 25

        /** Routes past the 32 keys of a map whose order the expander knows. */
        const val MANY_ROUTES = 50

        fun helpersOn(routes: Int): String = "RouterFixture.HelpersOn$routes"
        fun helpersOff(routes: Int): String = "RouterFixture.HelpersOff$routes"

        /** The file a router written here is in, in `lib/`. */
        fun fileName(router: String): String = router.substringAfter('.').replace(Regex("([a-z])([A-Z0-9])"), "$1_$2").lowercase()

        /** A router of [routes] `get` routes, each with a dynamic segment, with helpers on or off. */
        fun router(name: String, helpers: Boolean, routes: Int): String =
            "defmodule $name do\n  use Phoenix.Router, helpers: $helpers\n" +
                (0 until routes).joinToString("") { "  get \"/p$it/:id\", RouterFixture.C$it, :index\n" } +
                "end\n"

        /** The generated project, `null` on a leg other than its lock's, kept with a failure for the class. */
        val demo: Result<Compiled?> by lazy {
            runCatching {
                if (File(FIXTURE, "elixir-version").readText().trim() != LegManifest.environment("ELIXIR_VERSION")) return@runCatching null

                compile("phoenix-router-fixture", "phoenixRouterFixture") { project -> FileUtil.copyDir(FIXTURE, project) { it != SHAPES } }
            }
        }

        /** The shapes, `null` on a leg the `Phoenix.Component` fixture has no lock for, kept with a failure for the class. */
        val shapes: Result<Compiled?> by lazy {
            runCatching {
                if (!hasLegLock(COMPONENT_FIXTURE)) return@runCatching null

                compile("phoenix-router-shapes", "phoenixComponentFixture") { project ->
                    FileUtil.copyFileOrDir(File(COMPONENT_FIXTURE, "mix.exs"), File(project, "mix.exs"))
                    FileUtil.copyDir(File(COMPONENT_FIXTURE, "locks"), File(project, "locks"))
                    FileUtil.copyDir(File(FIXTURE, "oracle"), File(project, "oracle"))
                    FileUtil.copyDir(SHAPES, File(project, "lib"))
                    for ((helpers, routes) in listOf(true to HELPERS_COMPILED, true to MANY_ROUTES, false to MANY_ROUTES)) {
                        val name = if (helpers) helpersOn(routes) else helpersOff(routes)

                        File(project, "lib/${fileName(name)}.ex").writeText(router(name, helpers, routes))
                    }
                }
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

        /** [router] compiled, as Elixir's oracle gives it, in the form [ended] gives the expander's. */
        fun compiledByElixir(routers: OtpErlangMap, router: String): String =
            "$router compiled ${exported(entry(routers, router))}; routes: ${routesOf(routers, router)}"

        /** How [result], [router]'s, ended: compiled, with what it stores and its routes, or the ending's site. */
        fun ended(router: String, result: ExpansionResult): String =
            if (result.ended == ExpansionResult.Ended.Compiled) "$router compiled ${stored(result)}; routes: ${routes(result)}" else "$router ${ending(result)}"

        /** Elixir's `__routes__/0` of [router], each route's verb, path, plug and options. */
        fun routesOf(routers: OtpErlangMap, router: String): String =
            (field(routers, router, "routes") as OtpErlangList).elements().joinToString(", ", "[", "]", transform = ::inspect)

        /** The routes [result]'s `@phoenix_routes` holds once its body ends, oldest first, as [routesOf] gives Elixir's. */
        fun routes(result: ExpansionResult): String =
            when (val value = result.attributes.final["phoenix_routes"]) {
                is AttributeValue.Held -> (value.value as OtpErlangList).elements().reversed().joinToString(", ", "[", "]") { route ->
                    val struct = route as OtpErlangMap

                    inspect(OtpErlangTuple(ROUTE_FIELDS.map { struct.get(OtpErlangAtom(it)) }.toTypedArray()))
                }
                else -> "$value"
            }

        val ROUTE_FIELDS = listOf("verb", "path", "plug", "plug_opts")
    }
}
