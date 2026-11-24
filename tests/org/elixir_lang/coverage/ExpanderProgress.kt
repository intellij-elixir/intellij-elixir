package org.elixir_lang.coverage

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import org.elixir_lang.Module
import org.elixir_lang.coverage.MacroCensus.Side
import org.elixir_lang.elixir_surface.LegManifest
import org.elixir_lang.expander.Expansion
import org.elixir_lang.expander.ExpansionResult
import org.elixir_lang.expander.LegSdk
import org.elixir_lang.expander.PhoenixCorpus
import org.elixir_lang.expander.ProjectModules
import org.elixir_lang.expander.Written
import org.elixir_lang.expander.expansion
import org.elixir_lang.lowering.ElixirAst
import java.io.File
import com.intellij.openapi.module.Module as IdeaModule

/**
 * How far the expander gets through each module of a Mix project: the units it reached and expanded, how the module
 * ended and what stopped it first.
 */
internal class ExpanderProgress(
    val elixirVersion: String,
    val project: String,
    val withoutClasses: Int,
    val modules: List<ModuleRow>,
    val files: List<FileRow>,
    val loweringErrors: Map<String, Int>,
) {
    enum class Kind { COMPILED, TAINTED, RAISED, CRASHED, STOPPED }

    /** What stopped a module or a file's top level first, on which line, and why where it declined. */
    data class Stop(val cause: String, val line: Int, val declined: String? = null)

    data class ModuleRow(
        val side: Side,
        val library: String,
        val file: String,
        val module: String,
        val units: Int,
        val expanded: Int,
        val ended: Kind,
        val stop: Stop?,
        /** Where the module was first marked as written by code the expander doesn't follow ([marked]). */
        val marked: String?,
    )

    /** [top] is [EXPANDED] when nothing at the file's top level stopped. */
    data class FileRow(
        val side: Side,
        val library: String,
        val file: String,
        val top: String,
        val line: Int?,
        val declined: String?,
    )

    data class Total(
        val side: Side,
        val library: String,
        val files: Int,
        val modules: Int,
        val units: Int,
        val expanded: Int,
        val ended: Map<Kind, Int>,
    )

    fun totals(): List<Total> =
        (files.map { it.side to it.library } + modules.map { it.side to it.library })
            .distinct()
            .sortedWith(compareBy({ it.first }, { it.second }))
            .map { (side, library) ->
                val own = modules.filter { it.side == side && it.library == library }

                Total(
                    side,
                    library,
                    files.count { it.side == side && it.library == library },
                    own.size,
                    own.sumOf { it.units },
                    own.sumOf { it.expanded },
                    Kind.entries.associateWith { kind -> own.count { it.ended == kind } },
                )
            }

    /** Per side, each first stop's cause with the modules it ends, the commonest first. */
    fun stops(side: Side): List<Pair<String, Int>> =
        modules
            .filter { it.side == side }
            .mapNotNull { it.stop?.cause }
            .groupingBy { it }
            .eachCount()
            .toList()
            .sortedWith(compareBy({ -it.second }, { it.first }))

    fun report(): String {
        val lines = mutableListOf(
            row("# Elixir $elixirVersion", project, "dependencies without CLASSES: $withoutClasses"),
            "# units counts the units the expander reached, not the units in the source: definitions queued before " +
                "a body's stop still expand and count, and those after it are never queued",
            "# module: side, library, file, module, units, units expanded, ended, first stop, line, declined, marked",
            "# file: side, library, file, top-level outcome, line, declined",
            "# total: side, library, files, modules, units, units expanded, compiled, tainted, raised, crashed, stopped",
            "# stop: side, first stop, modules it ends",
        )

        if (loweringErrors.isNotEmpty()) {
            lines += "# ${loweringErrors.values.sum()} lowering errors logged, counted rather than failing the run"
            loweringErrors.forEach { (message, count) -> lines += row("#   $count", message) }
        }

        modules.forEach {
            lines += row(
                "module", side(it.side), it.library, it.file, it.module, it.units, it.expanded, it.ended.name.lowercase(),
                it.stop?.cause ?: "-", it.stop?.line ?: "-", it.stop?.declined ?: "-", it.marked ?: "-",
            )
        }
        files.filter { it.top != EXPANDED }.forEach {
            lines += row("file", side(it.side), it.library, it.file, it.top, it.line ?: "-", it.declined ?: "-")
        }
        totals().forEach {
            lines += row("total", side(it.side), it.library, it.files, it.modules, it.units, it.expanded,
                *Kind.entries.map { kind -> it.ended.getValue(kind) }.toTypedArray())
        }
        Side.entries.forEach { side ->
            stops(side).forEach { (cause, count) -> lines += row("stop", side(side), cause, count) }
        }

        return lines.joinToString("\n", postfix = "\n")
    }

    private fun side(side: Side): String = side.name.lowercase()

    /** A tab-separated row, in which a name that holds a tab, a line break or a backslash is escaped to stay in its column. */
    private fun row(vararg cells: Any): String =
        cells.joinToString("\t") {
            it.toString().replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n")
        }

    companion object {
        const val EXPANDED = "expanded"

        /**
         * Sets up the Mix project at [dir] in [module]: the leg's Elixir as its SDK, and its root, `lib`, `test` and
         * each dependency as [PhoenixCorpus] lays them out. Fails when indexing has not finished, since a lookup in dumb
         * mode answers unreadable and would read as a stop.
         */
        fun setUp(project: Project, module: IdeaModule, disposable: Disposable, dir: File): PhoenixCorpus {
            LegSdk.setUp(module, disposable)
            val corpus = PhoenixCorpus(project, disposable, dir)

            corpus.setUp(module)
            check(!DumbService.isDumb(project)) { "${dir.name} is still indexing, so every lookup would read as a stop" }

            return corpus
        }

        /**
         * Expands every file of [corpus]'s app, then of its dependencies, once each through one [ProjectModules], and
         * reports each module and file. Each dependency file is counted under the library of [module] whose sources
         * hold it.
         */
        fun run(project: Project, module: IdeaModule, corpus: PhoenixCorpus): ExpanderProgress {
            val libraries = corpus.read { dependencyLibraries(module) }
            val holder = ProjectModules(project)
            val modules = mutableListOf<ModuleRow>()
            val files = mutableListOf<FileRow>()

            corpus.quiet {
                for ((side, list) in listOf(Side.APP to corpus.appFiles, Side.DEP to corpus.depFiles)) {
                    for (file in list) {
                        val psi = corpus.psi(file) ?: error("no Elixir file at $file")
                        val library = if (side == Side.APP) corpus.dir.name else libraryOf(libraries, psi.virtualFile)
                        val path = FileUtil.toSystemIndependentName(file.relativeTo(corpus.dir).path)
                        val expansion = expansion(holder, psi)
                        val top = stopOf(expansion.top)

                        files += FileRow(side, library, path, top?.cause ?: EXPANDED, top?.line, top?.declined)
                        ProjectModules.results(expansion).forEach { result ->
                            val name = Module.indexName(result.module)

                            modules += ModuleRow(
                                side,
                                library,
                                path,
                                name,
                                result.units.size,
                                result.units.count { it.expansion is Expansion.Expanded },
                                kind(result.ended),
                                firstStop(result),
                                result.written?.let { marked(it, name) },
                            )
                        }
                    }
                }
            }

            return ExpanderProgress(
                LegManifest.environment("ELIXIR_VERSION"),
                corpus.dir.name,
                libraries.count { !it.hasClasses },
                modules,
                files,
                synchronized(corpus.loweringErrors) { corpus.loweringErrors.toMap() },
            )
        }

        private class DependencyLibrary(val name: String, val sources: List<VirtualFile>, val hasClasses: Boolean)

        private fun dependencyLibraries(module: IdeaModule): List<DependencyLibrary> =
            ModuleRootManager.getInstance(module).orderEntries
                .filterIsInstance<LibraryOrderEntry>()
                .mapNotNull { it.library }
                .map {
                    DependencyLibrary(
                        it.name!!,
                        it.getFiles(OrderRootType.SOURCES).toList(),
                        it.getFiles(OrderRootType.CLASSES).isNotEmpty()
                    )
                }

        private fun libraryOf(libraries: List<DependencyLibrary>, file: VirtualFile): String =
            libraries
                .filter { library -> library.sources.any { VfsUtilCore.isAncestor(it, file, false) } }
                .also { check(it.size == 1) { "${file.path} is in ${it.size} dependency libraries' sources" } }
                .single()
                .name

        private fun kind(ended: ExpansionResult.Ended): Kind =
            when (ended) {
                ExpansionResult.Ended.Compiled -> Kind.COMPILED
                ExpansionResult.Ended.Tainted -> Kind.TAINTED
                is ExpansionResult.Ended.Raised -> Kind.RAISED
                is ExpansionResult.Ended.Crashed -> Kind.CRASHED
                is ExpansionResult.Ended.Stopped -> Kind.STOPPED
            }

        private fun line(at: ElixirAst): Int = at.meta.start.line

        /** The stop [expansion] is, or `null` where it expanded. */
        private fun stopOf(expansion: Expansion): Stop? =
            when (expansion) {
                is Expansion.Expanded -> null
                is Expansion.Error -> Stop("error ${expansion.kind}", line(expansion.at))
                is Expansion.Unported -> Stop(cause(expansion), line(expansion.at), expansion.declined)
                is Expansion.Opaque -> Stop(cause(expansion), line(expansion.at), expansion.declined)
            }

        /** What an unexpanded node is: a macro without a model, or a construct not ported. */
        private fun cause(expansion: Expansion): String =
            when (expansion) {
                is Expansion.Opaque -> "opaque ${macroName(expansion.dispatch)}"
                is Expansion.Unported -> "unported ${shape(expansion.at)}"
                is Expansion.Expanded, is Expansion.Error -> error("$expansion is not a stop")
            }

        private val Expansion.stoppedAt: ElixirAst?
            get() = when (this) {
                is Expansion.Expanded -> null
                is Expansion.Error -> null
                is Expansion.Unported -> at
                is Expansion.Opaque -> at
            }

        /** [written] as `<call> line <N>`, ` in <module>` where its module isn't [rowModule], then what declined. */
        private fun marked(written: Written, rowModule: String): String {
            val within = written.module?.let { Module.indexName(it) }?.takeIf { it != rowModule }

            return "${shape(written.at)} line ${line(written.at)}" +
                within?.let { " in $it" }.orEmpty() +
                written.declined?.let { ": $it" }.orEmpty()
        }

        /** The one place a module's first stop is decided. */
        private fun firstStop(result: ExpansionResult): Stop? =
            when (val ended = result.ended) {
                ExpansionResult.Ended.Compiled, ExpansionResult.Ended.Tainted -> null
                is ExpansionResult.Ended.Raised -> Stop("raised ${ended.error.kind}", line(ended.error.at))
                is ExpansionResult.Ended.Crashed ->
                    Stop("crashed ${ended.error.kind} ${ended.exception}", line(ended.error.at))
                is ExpansionResult.Ended.Stopped -> {
                    val at = ended.at
                    val reached = result.units.map { it.expansion }.firstOrNull { it.stoppedAt === at }
                        ?: result.opaque.firstOrNull { it.at === at }

                    reached?.let(::stopOf) ?: run {
                        val nested = result.nested.any { (it.ended as? ExpansionResult.Ended.Stopped)?.at === at }

                        Stop(if (nested) "nested" else "other ${shape(at)}", line(at))
                    }
                }
            }

        /**
         * A call as `name/arity` (`name` where it has no arguments), a remote call on a module as `Module.name/arity`,
         * and any other node as its class's name. The module is an alias as written or an atom, such as a
         * `@before_compile` callback's, which is shown as [Module.indexName] shows it.
         */
        internal fun shape(node: ElixirAst): String {
            if (node is ElixirAst.Call) {
                val arity = node.arguments?.let { "/${it.size}" } ?: ""
                val callee = node.callee

                if (callee is ElixirAst.Literal.Atom) return callee.name + arity

                if (callee is ElixirAst.Call && (callee.callee as? ElixirAst.Literal.Atom)?.name == ".") {
                    val receiver = callee.arguments?.getOrNull(0)
                    val name = callee.arguments?.getOrNull(1)
                    val segments = (receiver as? ElixirAst.Alias)?.segments?.map { (it as? ElixirAst.Literal.Atom)?.name }
                    val module = when {
                        segments != null && segments.all { it != null } -> segments.joinToString(".")
                        receiver is ElixirAst.Literal.Atom -> Module.indexName(receiver.name)
                        else -> null
                    }

                    if (name is ElixirAst.Literal.Atom && module != null) return "$module.${name.name}$arity"
                }
            }

            return node.javaClass.simpleName
        }
    }
}
