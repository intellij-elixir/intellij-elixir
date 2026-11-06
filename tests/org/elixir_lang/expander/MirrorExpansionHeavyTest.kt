package org.elixir_lang.expander

import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.IndexingTestUtil
import org.elixir_lang.elixir_surface.LegManifest.environment
import org.elixir_lang.expander.MirrorPassRows.endedError
import org.elixir_lang.expander.MirrorPassRows.lowered
import org.elixir_lang.expander.MirrorPassRows.ownedErrors
import org.elixir_lang.expander.MirrorPassRows.ownedStubs
import org.elixir_lang.expander.MirrorPassRows.written
import org.elixir_lang.junit.HeavyTestCase
import java.io.File

/**
 * Expands the decompiled source of every `.beam` in the leg's Elixir and OTP apps, and, with [DEPS] naming a Mix
 * build's `lib` directory, of every dependency's. Fails for each mirror whose pass output still holds a stub the pass
 * owns, read from the output, as a mirror that raises earlier hides what follows; and for each that raises what such a
 * stub raises. Writes those, then every other raise by kind and root, then each one, to
 * `build/reports/mirror-expansion/<elixir version>.txt`.
 *
 * It runs only when [PROPERTY] is `true`, which Gradle's `-PexpanderMirror=true` sets; `-PexpanderMirrorDeps=<dir>`
 * sets [DEPS].
 */
class MirrorExpansionHeavyTest : HeavyTestCase() {
    override fun shouldRunTest(): Boolean {
        if (!java.lang.Boolean.getBoolean(PROPERTY)) {
            println("${javaClass.simpleName}.$name skipped: -PexpanderMirror=true ($PROPERTY) is not set")
            return false
        }

        return super.shouldRunTest()
    }

    override fun setUp() {
        super.setUp()
        LegSdk.setUp(module, testRootDisposable)
    }

    override fun tearDown() {
        try {
            LegSdk.tearDown(module)
        } finally {
            super.tearDown()
        }
    }

    fun testNoMirrorHoldsOrRaisesWhatThePassOwns() {
        val deps = System.getProperty(DEPS)?.let(::File)
        val ebins = deps?.let(::depEbins).orEmpty()
        val beams = appBeams.values.sortedBy { it.path } + ebins.flatMap { ebin -> beamsIn(ebin).sortedBy { it.name } }
        allowAppBeamRoots(testRootDisposable)
        if (deps != null) {
            VfsRootAccess.allowRootAccess(testRootDisposable, deps.path)
            addDeps(ebins)
        }
        val roots = Roots(deps)
        val owned = mutableListOf<String>()
        val raised = mutableListOf<Raise>()
        var holder = ProjectModules(project)

        for ((index, beam) in beams.withIndex()) {
            // A fresh holder now and then, so the clauses and PSI it holds don't grow with the run.
            if (index % BATCH == 0 && index > 0) {
                holder = ProjectModules(project)
                dropPsi(project)
            }
            val mirror = mirrorOf(project, beam) ?: continue
            val written = written(mirror)
            owned += ownedStubs(compilerOutput(lowered(mirror)) { written }, written).map { "${beam.path}: holds $it" }
            val expansion = expansion(holder, mirror)
            // One line a module, `<module> raised <kind> `<node>`...`, which names the node in the mirror's text.
            val rendered by lazy { render(mirror, expansion).lines().drop(1) }
            for (result in expansion.modules) {
                owned += ownedErrors(result).map { "${beam.path}: ${result.module} reports $it" }
                endedError(result)?.let { (how, error) ->
                    val line = rendered.firstOrNull { it.startsWith("${result.module} ") } ?: result.module
                    raised += Raise(roots.of(beam), "$how ${error.kind}", "${beam.path}: $line")
                }
            }
        }

        val census = raised.groupingBy { "${it.kind} ${it.root}" }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        File("build/reports/mirror-expansion").apply { mkdirs() }.resolve("${environment("ELIXIR_VERSION")}.txt").writeText(
            "beams: ${beams.size}; deps: ${deps ?: "not set"}; owned: ${owned.size}; raised: ${raised.size}\n" +
                owned.joinToString("") { "$it\n" } +
                census.joinToString("") { "${it.value} ${it.key}\n" } +
                raised.joinToString("") { "${it.line}\n" }
        )
        println("${javaClass.simpleName}: ${beams.size} beams, ${owned.size} owned, ${raised.size} raised")
        assertEquals(owned.joinToString("\n"), 0, owned.size)
    }

    private class Raise(val root: String, val kind: String, val line: String)

    /** The run's roots: the Elixir SDK, the Erlang SDK and [deps]. */
    private class Roots(deps: File?) {
        private val deps = deps?.canonicalPath
        private val erlang = File(environment("ERLANG_SDK_HOME")).canonicalPath

        /** Which root [beam] is in. */
        fun of(beam: File): String {
            val path = beam.canonicalPath

            return when {
                deps != null && path.startsWith(deps) -> "deps"
                path.startsWith(erlang) -> "erlang"
                else -> "elixir"
            }
        }
    }

    /** [ebins] as the module's library, as a Mix project's deps are, so a struct in one is found. */
    private fun addDeps(ebins: List<File>) {
        val urls = ebins.map { LocalFileSystem.getInstance().refreshAndFindFileByIoFile(it)!!.url }
        ModuleRootModificationUtil.addModuleLibrary(module, "deps", urls, emptyList())
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    /** Each app's `ebin` in [lib], a Mix build's `_build/<env>/lib`. */
    private fun depEbins(lib: File): List<File> = appEbins(lib).sortedBy { it.parentFile.name }.filter { it.isDirectory }

    private companion object {
        const val PROPERTY = "org.elixir_lang.expander.mirror"
        const val DEPS = "org.elixir_lang.expander.mirror.deps"
        const val BATCH = 200
    }
}
