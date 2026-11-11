package org.elixir_lang.expander

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.io.IoTestUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.PlatformTestUtil
import org.elixir_lang.ElixirFileType
import org.elixir_lang.beam.BeamLibraryFixture
import org.elixir_lang.expander.ExpanderSourceRows.read
import org.elixir_lang.injection.ElixirSigilInjector
import org.elixir_lang.junit.HeavyTestCase
import org.elixir_lang.junit.logs.GuardedLoggedErrorProcessor
import org.elixir_lang.mix.sync.MixSyncTestHelpers
import org.elixir_lang.mix.sync.consolidatedLibraryName
import org.elixir_lang.model.psi.config.disableMixDepsCheck
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.settings.ElixirExperimentalSettings
import java.io.File
import java.nio.file.Files
import org.elixir_lang.mix.Project as MixProject

/**
 * A held answer is recomputed when something it read can have changed, and only then. Each row makes one change to a
 * Mix project and asks a holder that answered before the change, and a new holder, the same questions after it: their
 * answers must agree, and must differ from the first answers where the change reaches them. A module apart from the
 * change keeps its held expansion, unless the row says the change reaches it too.
 *
 * Every row runs before the test fails, so one failure doesn't hide the rest.
 */
class HeldAgainstFreshHeavyTest : HeavyTestCase() {
    private lateinit var dir: File
    private lateinit var inputs: ProjectModules.Inputs
    private lateinit var depBuild: File
    private lateinit var testDepBuild: File
    private lateinit var plainBuild: File
    private lateinit var ownBuild: File
    private lateinit var testOwnBuild: File
    private lateinit var consolidated: File
    private lateinit var standIn: File
    private lateinit var otherModule: Module

    override fun setUp() {
        super.setUp()
        disableMixDepsCheck(testRootDisposable)
        dir = createTempDir("held").also(::layOut)
        VfsRootAccess.allowRootAccess(testRootDisposable, dir.path)
        val sdk = LegSdk.setUp(module, testRootDisposable)
        WriteAction.runAndWait<Throwable> { ProjectRootManager.getInstance(project).projectSdk = sdk }
        open(sdk)
        val probe = vf(File(dir, "mix.exs"))
        inputs = (ReadAction.computeBlocking<SdkInputs.Answer, Throwable> {
            SdkInputs.getInstance(project).inputsFor(PsiManager.getInstance(project).findFile(probe)!!)
        } as SdkInputs.Answer.Ready).inputs
    }

    override fun tearDown() {
        try {
            WriteAction.runAndWait<Throwable> { ProjectRootManager.getInstance(project).projectSdk = null }
            LegSdk.tearDown(module)
        } finally {
            super.tearDown()
        }
    }

    fun testHeldAnswersAgreeWithFreshOnes() {
        rows()
        val results = rows.mapIndexed { i, row -> run(i + 1, row) }
        println(results.joinToString("\n") { it.line })
        val failed = results.filter { it.failed }
        if (failed.isNotEmpty()) fail("${failed.size} of ${results.size} rows failed:\n" + failed.joinToString("\n") { it.line })
    }

    // The project

    /** The app `fix`, with one path dep, `dep1`, compiled into `_build/dev`. */
    private fun layOut(root: File) {
        fun write(relative: String, text: String) = File(root, relative).apply { parentFile.mkdirs() }.writeText(text)

        write("mix.exs", mixExs("Fix", "fix", ", deps: [{:dep1, path: \"deps/dep1\"}]"))
        write("config/config.exs", "import Config\n\nconfig :fix, base: 1\n")
        write("deps/dep1/mix.exs", mixExs("Dep1", "dep1"))
        write("deps/dep1/lib/dep1.ex", "defmodule Dep1 do\n  def f, do: 1\nend\n")
        listOf("lib", "test", "other/lib", "outside", "_build/dev/lib/fix/ebin", "_build/dev/lib/fix/consolidated", "_build/test/lib/fix/ebin", "_build/test/lib/dep1/ebin")
            .forEach { File(root, it).mkdirs() }
        File(BEAMS, "Elixir.Dep1.beam").copyTo(File(root, "_build/dev/lib/dep1/ebin/Elixir.Dep1.beam"))
    }

    /** A `mix.exs` defining [module]`.MixProject`, whose project is [app] at 0.1.0 with [more] after. */
    private fun mixExs(module: String, app: String, more: String = "") =
        "defmodule $module.MixProject do\n  use Mix.Project\n\n  def project, do: [app: :$app, version: \"0.1.0\"$more]\nend\n"

    /** As Mix import and the deps sync shape a project: the app's own build in content, a dep's excluded and a library. */
    private fun open(sdk: Sdk) {
        depBuild = File(dir, "_build/dev/lib/dep1/ebin")
        testDepBuild = File(dir, "_build/test/lib/dep1/ebin")
        plainBuild = File(dir, "_build/dev/lib/fix/ebin")
        // A sibling app's build: in content and on the code path, which is what `isDependencyOutput` tells apart.
        ownBuild = File(dir, "_build/dev/lib/umb/ebin").apply { mkdirs() }
        testOwnBuild = File(dir, "_build/test/lib/umb/ebin").apply { mkdirs() }
        consolidated = File(dir, "_build/dev/lib/fix/consolidated")
        standIn = File(createTempDir("sdk-stand-in"), "ebin").apply { mkdirs() }
        VfsRootAccess.allowRootAccess(testRootDisposable, standIn.path)
        val root = vf(dir)
        ModuleRootModificationUtil.updateModel(module) { model ->
            MixProject.addFolders(model, root)
            model.sdk = sdk
            val content = model.contentEntries.first()
            listOf("_build/dev/lib/dep1", "_build/test/lib/dep1", "outside", "other").forEach { content.addExcludeFolder(vf(File(dir, it))) }
        }
        // The syncs the fixture's own files queue, before the libraries are seeded.
        MixSyncTestHelpers.awaitMixDepsSyncs(project)
        otherModule = WriteAction.computeAndWait<Module, Throwable> {
            ModuleManager.getInstance(project).newModule(File(dir, "other/other.iml").toPath(), ModuleType.get(module).id)
        }
        ModuleRootModificationUtil.updateModel(otherModule) { model ->
            model.addContentEntry(vf(File(dir, "other"))).addSourceFolder(vf(File(dir, "other/lib")), false)
            model.sdk = sdk
        }
        val dep = library("dep-dep1", listOf(depBuild, testDepBuild), listOf(File(dir, "deps/dep1/lib")))
        // Under the name a sync gives it, which a sync then leaves alone, as it would one it made.
        val cons = library(consolidatedLibraryName(project, root.url), listOf(consolidated), emptyList())
        val stand = library("sdk-stand-in", listOf(standIn), emptyList())
        val umb = library("dep-umb", listOf(ownBuild, testOwnBuild), emptyList())
        listOf(dep, cons, stand, umb).forEach { ModuleRootModificationUtil.addDependency(otherModule, it) }
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    /** [name] made and added to [module] by [BeamLibraryFixture.addLibrary], for [otherModule] to add too. */
    private fun library(name: String, classes: List<File>, sources: List<File>): Library {
        BeamLibraryFixture.addLibrary(project, module, name, classes.map(::vf), sources.map(::vf))
        return LibraryTablesRegistrar.getInstance().getLibraryTable(project).getLibraryByName(name)!!
    }

    private fun vf(file: File): VirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)!!.also {
        VfsUtil.markDirtyAndRefresh(false, true, true, it)
    }

    /** Every pending commit, Mix deps sync and index update done, so that none lands in a later row. */
    private fun settle() {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        MixSyncTestHelpers.awaitMixDepsSyncs(project)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    // Answers, rendered with no PSI identity

    private fun rel(file: VirtualFile): String = file.path.removePrefix(FileUtil.toSystemIndependentName(dir.path) + "/")

    private fun render(struct: ModuleStruct): String =
        when (struct) {
            is ModuleStruct.Present -> "fields=${struct.fields.sorted()}"
            else -> "$struct"
        }

    private fun render(clauses: ModuleClauses?): String =
        clauses?.let { c -> "${c.module} ${c.source} ${c.definitions.keys.map { "$it" }.sorted()} ${c.file?.let(::rel)}" } ?: "null"

    private fun render(result: ExpansionResult): String =
        "${result.module} defs=${result.table.entries.keys.map { "$it" }.sorted()} consulted=${result.consulted.sorted()} " +
            "errors=${result.errors.size} struct=${result.struct?.let(::render)} certain=${result.certain} known=${result.definitionsKnown} " +
            "nested=[${result.nested.joinToString { render(it) }}]"

    private fun render(located: ProjectModules.Located): String =
        when (located) {
            ProjectModules.Located.Unknown -> "Unknown"
            ProjectModules.Located.None -> "None"
            is ProjectModules.Located.Several -> "Several${located.copies.map(::rel).sorted()}"
            is ProjectModules.Located.Compiled ->
                "Compiled(${rel(located.beam)} ${exported(located.exports)} source=${located.source?.virtualFile?.let(::rel)})"
            is ProjectModules.Located.Source -> "Source(${located.file.virtualFile?.let(::rel)} ${located.result?.module})"
        }

    private fun render(expansion: FileExpansion): String =
        "${expansion.top.javaClass.simpleName} endsRun=${expansion.endsRun} modules=[${expansion.modules.joinToString { render(it) }}]"

    /** [body]'s answer, or what it threw, so that one question throwing is an answer the two holders can disagree on. */
    private inline fun attempt(body: () -> String): String =
        try {
            body()
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            "THREW ${e.javaClass.simpleName}: ${e.message?.lines()?.first()?.take(120)}"
        }

    private fun answers(holder: ProjectModules, scene: Scene): Map<String, String> =
        read {
            buildMap {
                for (asker in scene.askers) {
                    for (atom in scene.atoms) {
                        put("${asker.name} exports $atom", attempt { exported(holder.exports(asker, atom, inputs)) })
                        put("${asker.name} struct $atom", attempt { render(holder.struct(asker, atom, inputs)) })
                        put("${asker.name} clauses $atom", attempt { render(holder.clauses(asker, atom, inputs)) })
                        put("${asker.name} locate $atom", attempt { render(holder.locate(asker, atom, inputs)) })
                    }
                }
                for (file in scene.expansions) {
                    put("expansion ${file.name}", attempt { render(holder.expansion(file, inputs)) })
                }
            }
        }

    // Rows

    /**
     * @property askers the files asking about [atoms]
     * @property expansions the files whose expansions are compared
     * @property control a file apart from the change, whose held expansion is kept or not as the row's [Row.control] says
     */
    private class Scene(val askers: List<PsiFile>, val atoms: List<String>, val expansions: List<ElixirFile>, val control: ElixirFile)

    /** How a file's text changes: in the editor, in the editor then saved, through the VFS with PSI, or with none held. */
    private enum class Route { PSI, SAVE, VFS_PSI, VFS_NOPSI }

    /** Whether the row's change reaches the control's held expansion. */
    private enum class Control { HELD, RECOMPUTED }

    private class Row(
        val group: String,
        val id: String,
        val event: String,
        val changes: Boolean,
        val control: Control,
        val setup: Ctx.() -> Pair<Scene, Ctx.() -> Unit>,
    )

    private class Result(val line: String, val failed: Boolean)

    private val rows = ArrayList<Row>()

    /** A row: [setup] lays out its files and gives the scene and the change. [changes] is whether the change reaches its answers. */
    private fun row(
        group: String,
        id: String,
        event: String,
        changes: Boolean = true,
        control: Control = Control.HELD,
        setup: Ctx.() -> Pair<Scene, Ctx.() -> Unit>,
    ) {
        rows += Row(group, id, event, changes, control, setup)
    }

    private inner class Ctx(val n: Int) {
        /** What the row opened, closed before the next row. */
        val disposable = Disposer.newDisposable("row $n")

        /** What the row holds until it ends, as an editor would. */
        val kept = ArrayList<Any>()
        val p = "S$n"
        val folder = "lib/s$n"

        /** What the row made outside [folder], deleted when it ends: a later row would find it in its scope. */
        private val made = LinkedHashSet<File>()

        fun write(relative: String, text: String): VirtualFile {
            val file = File(dir, relative)
            making(file)
            file.parentFile.mkdirs()
            file.writeText(text)

            return vf(file)
        }

        /** Notes the outermost of [file] and its directories that doesn't exist yet. */
        private fun making(file: File) {
            val outermost = generateSequence(file) { it.parentFile }.takeWhile { !it.exists() }.lastOrNull() ?: return
            if (!outermost.startsWith(File(dir, folder))) made += outermost
        }

        fun deleteMade() {
            for (file in made.reversed()) {
                LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)?.let(::delete)
            }
        }

        fun src(name: String, text: String, under: String = folder): VirtualFile = write("$under/$name", text)

        /** The app's IDE module depending on [otherModule] until the row ends, as an umbrella app does on a sibling. */
        fun dependOnOther() {
            ModuleRootModificationUtil.addDependency(module, otherModule)
            Disposer.register(disposable) {
                ModuleRootModificationUtil.updateModel(module) { model ->
                    model.findModuleOrderEntry(otherModule)?.let(model::removeOrderEntry)
                }
            }
        }

        fun mod(name: String, body: String = "def f, do: 1") = "defmodule $p.$name do\n  $body\nend\n"

        fun psi(file: VirtualFile): PsiFile = ReadAction.computeBlocking<PsiFile, Throwable> { PsiManager.getInstance(project).findFile(file)!! }

        fun elixir(file: VirtualFile): ElixirFile = psi(file) as ElixirFile

        fun asker(reads: String, name: String = "asker.ex", under: String = folder): ElixirFile =
            elixir(src(name, "defmodule $p.Asker do\n  def run, do: $reads\nend\n", under))

        /** A module and a reader of it that the row's change doesn't touch. */
        fun control(): ElixirFile {
            src("control.ex", "defmodule $p.Control do\n  defmacro m, do: 1\nend\n")

            return elixir(src("control_user.ex", "defmodule $p.ControlUser do\n  require $p.Control\n  def run, do: $p.Control.m()\nend\n"))
        }

        /** A [Scene] whose control is [control]'s. */
        fun scene(askers: List<PsiFile>, atoms: List<String>, expansions: List<ElixirFile> = emptyList()) =
            Scene(askers, atoms, expansions, control())

        /** A [scene] of an asker of `A`, and another whose expansion is compared when [expanded]. */
        fun aScene(expanded: Boolean = false) =
            scene(listOf(asker("$p.A.f()")), listOf(atom("A")), if (expanded) listOf(asker("$p.A.f()", "user.ex")) else emptyList())

        /** A [scene] of an asker of [asked]'s `f/0`, of `A` and `B`, comparing the expansion of an asker of `B`. */
        fun abScene(asked: String) =
            scene(listOf(asker("$p.$asked.f()")), listOf(atom("A"), atom("B")), listOf(asker("$p.B.f()", "user.ex")))

        /** A [scene] of an asker of `HeldPool.N<i>`. */
        fun poolScene(i: Int) = scene(listOf(asker("HeldPool.N$i.f(1)")), listOf(poolAtom(i)))

        /** A [scene] of an asker of `HeldPool.R<i>`. */
        fun pool2Scene(i: Int) = scene(listOf(asker("HeldPool.R$i.f(1)")), listOf(pool2Atom(i)))

        /** A source of `HeldPool.N<i>` defining only `only_source/0`. */
        fun onlySource(i: Int) = "defmodule HeldPool.N$i do\n  def only_source, do: 1\nend\n"

        /** A Mix project of the row's own, `sub<n>`. */
        fun subProject(): VirtualFile =
            write("sub$n/mix.exs", mixExs("Sub$n", "sub$n"))

        /** A config at [relative] setting `sub<n>`'s `:k` to 2, as [subReader] reads it. */
        fun subConfig(relative: String = "sub$n/config/config.exs"): VirtualFile = write(relative, "import Config\n\nconfig :sub$n, k: 2\n")

        /** A [scene] of an asker of [reader]'s `two/0`, comparing [reader]'s expansion. */
        fun subScene(reader: ElixirFile) = scene(listOf(asker("$p.Reader.two()")), listOf(atom("Reader")), listOf(reader))

        /** A file under [under] that defines `two/0` when `sub<n>`'s `:k` is 2. */
        fun subReader(under: String = "sub$n/lib"): ElixirFile = elixir(write("$under/reader.ex", "defmodule $p.Reader do\n  if Application.compile_env(:sub$n, :k) == 2 do\n    def two, do: 1\n  end\nend\n"))

        fun atom(name: String) = "Elixir.$p.$name"

        /** A module with one version, `HeldPool.N<i>`. */
        fun pool(): Int = ++oneVersion

        /** A module with two versions, `HeldPool.R<i>`, the second exporting `g/1` too. */
        fun pool2(): Int = ++twoVersions

        fun poolAtom(i: Int) = "Elixir.HeldPool.N$i"

        fun pool2Atom(i: Int) = "Elixir.HeldPool.R$i"

        fun beam(i: Int, to: File, name: String = "Elixir.HeldPool.N$i.beam"): VirtualFile = copy("v1/Elixir.HeldPool.N$i.beam", to, name)

        fun beam2(i: Int, to: File, version: String = "v1"): VirtualFile = copy("$version/Elixir.HeldPool.R$i.beam", to, "Elixir.HeldPool.R$i.beam")

        fun copy(from: String, to: File, name: String): VirtualFile {
            to.mkdirs()
            val target = File(to, name)
            File(BEAMS, from).copyTo(target, overwrite = true)

            return vf(target).also { vf(to) }
        }

        fun route(how: Route, file: VirtualFile, text: String) {
            when (how) {
                Route.PSI, Route.SAVE -> {
                    val document = ReadAction.computeBlocking<Document, Throwable> { FileDocumentManager.getInstance().getDocument(file)!! }
                    // As an editor holds it: with none held, the commit has no tree to change.
                    kept += psi(file)
                    WriteCommandAction.runWriteCommandAction(project) {
                        document.setText(text)
                        PsiDocumentManager.getInstance(project).commitDocument(document)
                        if (how == Route.SAVE) FileDocumentManager.getInstance().saveDocument(document)
                    }
                }
                Route.VFS_PSI, Route.VFS_NOPSI -> {
                    if (how == Route.VFS_NOPSI) noPsi(file)
                    WriteAction.runAndWait<Throwable> { VfsUtil.saveText(file, text) }
                }
            }
        }

        /** [file] with no PSI held, as a file written by a build or another tool has. */
        fun noPsi(file: VirtualFile) {
            check(psiLetGo(project, file)) { "${rel(file)} still has PSI" }
        }

        fun rename(file: VirtualFile, to: String) = WriteAction.runAndWait<Throwable> { file.rename(this, to) }

        fun move(file: VirtualFile, to: VirtualFile) = WriteAction.runAndWait<Throwable> { file.move(this, to) }

        fun delete(file: VirtualFile) = WriteAction.runAndWait<Throwable> { file.delete(this) }

        fun folderOf(relative: String): VirtualFile = vf(File(dir, relative).also(::making).apply { mkdirs() })

        /** On disk behind the VFS, then one refresh: what a build or a branch switch shows it. */
        fun disk(what: () -> Unit) {
            what()
            vf(dir)
        }
    }

    private var oneVersion = 0
    private var twoVersions = 0
    private val logged = ArrayList<String>()

    private fun run(n: Int, row: Row): Result {
        logged.clear()
        val result = LoggedErrorProcessor.executeWith(attributing).use {
            try {
                compare(n, row)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                Result(listOf(row.group, row.id, "RAISED ${e.javaClass.simpleName}: ${e.message?.lines()?.first()?.take(200)}").joinToString("\t"), true)
            }
        }

        return if (logged.isEmpty()) result else Result(result.line + "\tLOGGED " + logged.joinToString(" | "), true)
    }

    /** Records which row logged what, and fails the test for it as it would without this. */
    private val attributing = object : GuardedLoggedErrorProcessor() {
        override fun processWarn(category: String, message: String, t: Throwable?): Boolean {
            if (category.startsWith("#org.elixir_lang")) note("WARN", category, message)
            return super.processWarn(category, message, t)
        }

        override fun processError(category: String, message: String, details: Array<out String>, t: Throwable?): Set<Action> {
            note("ERROR", category, message)
            return super.processError(category, message, details, t)
        }

        private fun note(level: String, category: String, message: String) {
            synchronized(logged) { logged += "$level $category ${message.lines().first().take(160)}" }
        }
    }

    private fun compare(n: Int, row: Row): Result {
        val ctx = Ctx(n)

        return try {
            compare(ctx, row)
        } finally {
            Disposer.dispose(ctx.disposable)
            FileDocumentManager.getInstance().saveAllDocuments()
            ctx.deleteMade()
        }
    }

    private fun compare(ctx: Ctx, row: Row): Result {
        val (scene, change) = ctx.(row.setup)()
        settle()
        val held = ProjectModules(project)
        val before = answers(held, scene)
        val controlBefore = read { held.expansion(scene.control, inputs) }
        answers(held, scene)
        ctx.change()
        settle()
        val after = answers(held, scene)
        val controlAfter = read { held.expansion(scene.control, inputs) }
        val fresh = answers(ProjectModules(project), scene)
        val differs = fresh.keys.filter { fresh[it] != after[it] }
        val changed = fresh.keys.filter { fresh[it] != before[it] }
        val answer = when {
            differs.isNotEmpty() -> "STALE"
            row.changes && changed.isEmpty() -> "VACUOUS"
            !row.changes && changed.isNotEmpty() -> "CHANGED"
            else -> "ok"
        }
        val control = if (controlAfter === controlBefore) Control.HELD else Control.RECOMPUTED
        val detail = differs.firstOrNull()?.let { "$it: HELD ${after[it]?.take(160)} FRESH ${fresh[it]?.take(160)}" }
            ?: changed.firstOrNull()?.let { "$it: ${before[it]?.take(100)} -> ${fresh[it]?.take(100)}" }
            ?: if (answer == "VACUOUS") fresh.entries.joinToString(" ; ") { (k, v) -> "$k = ${v.take(300)}" } else ""
        val controlVerdict = if (control == row.control) "control ok" else "control ${control.name.lowercase()}, expected ${row.control.name.lowercase()}"

        return Result(
            listOf(row.group, row.id, "answer $answer", controlVerdict, "changed=${changed.size}", "differs=${differs.size}", detail, row.event).joinToString("\t"),
            answer != "ok" || control != row.control,
        )
    }

    // The rows

    private fun rows() {
        typeChanges()
        routes()
        beams()
        plainOwnBeams()
        askers()
        created()
        sourceFiles()
        definitionForms()
        filtered()
        chains()
        project()
        negatives()
        sites()
        builds()
    }

    private fun typeChanges() {
        row("type", "txt-to-ex", "a `.txt` renamed to `.ex`") {
            val txt = src("a.txt", mod("A"))
            aScene(expanded = true) to { rename(txt, "a.ex") }
        }
        row("type", "ex-to-txt", "a `.ex` renamed to `.txt`") {
            val ex = src("a.ex", mod("A"))
            aScene(expanded = true) to { rename(ex, "a.txt") }
        }
        row("type", "ex-to-txt-no-psi", "a `.ex` with no PSI held renamed to `.txt`") {
            val ex = src("a.ex", mod("A"))
            aScene(expanded = true) to { noPsi(ex); rename(ex, "a.txt") }
        }
    }

    private fun routes() {
        for (how in Route.entries) {
            val r = how.name.lowercase()
            row("second-copy", "several-to-one-$r", "F and G define A, F stops, by $r") {
                val f = src("f.ex", mod("A"))
                src("g.ex", mod("A", "def g, do: 1"))
                aScene() to { route(how, f, "defmodule $p.Other do\nend\n") }
            }
            row("second-copy", "own-beam-beside-$r", "F defines a name with the app's own `.beam`, F stops, by $r") {
                val i = pool()
                beam(i, ownBuild)
                val f = src("f.ex", onlySource(i))
                poolScene(i) to { route(how, f, "defmodule $p.Other do\nend\n") }
            }
            row("second-copy", "dep-beam-fallback-$r", "F defines a dep's `.beam`'s name, F stops, by $r") {
                val i = pool()
                beam(i, depBuild)
                val f = src("f.ex", onlySource(i))
                poolScene(i) to { route(how, f, "defmodule $p.Other do\nend\n") }
            }
            row("module", "renamed-$r", "a file's module renamed, by $r") {
                val f = src("f.ex", mod("A"))
                abScene("A") to { route(how, f, mod("B")) }
            }
            row("module", "added-$r", "a module added to a file, by $r") {
                val f = src("f.ex", mod("A"))
                abScene("B") to { route(how, f, mod("A") + mod("B")) }
            }
            row("module", "removed-$r", "a module removed from a file, by $r") {
                val f = src("f.ex", mod("A") + mod("B"))
                abScene("B") to { route(how, f, mod("A")) }
            }
            row("module", "body-$r", "a body changed, names kept, by $r") {
                val f = src("f.ex", mod("A"))
                aScene(expanded = true) to { route(how, f, mod("A", "def g, do: 1")) }
            }
        }
        row("second-copy", "several-to-one-renamed-txt", "F and G define A, F with no PSI held renamed to `.txt`") {
            val f = src("f.ex", mod("A"))
            src("g.ex", mod("A", "def g, do: 1"))
            aScene() to { noPsi(f); rename(f, "f.txt") }
        }
        row("second-copy", "deleted-never-opened","F and G define A, F never opened, F deleted, with the entry held") {
            val f = src("f.ex", mod("A"))
            src("g.ex", mod("A", "def g, do: 1"))
            aScene() to { noPsi(f); delete(f) }
        }
        row("module", "removed-stub-only", "a module removed from a file whose PSI is held with no tree loaded, by the VFS") {
            val f = src("f.ex", mod("A") + mod("B"))
            abScene("B") to {
                // The lookups loaded a tree: drop that PSI, then hold one built from the stubs alone.
                noPsi(f)
                val stubOnly = psi(f) as PsiFileImpl
                check(!stubOnly.isContentsLoaded) { "${rel(f)} has its tree loaded" }
                kept += stubOnly
                WriteAction.runAndWait<Throwable> { VfsUtil.saveText(f, mod("A")) }
            }
        }
    }

    private fun beams() {
        for ((where, to) in listOf<Pair<String, () -> File>>(
            "dep" to { depBuild },
            "own" to { ownBuild },
            "sdk-stand-in" to { standIn },
            "other-env-dep" to { testDepBuild },
            "other-env-own" to { testOwnBuild },
            "consolidated" to { consolidated },
        )) {
            row("beam", "beam-created-$where", "a `.beam` created under $where") {
                val i = pool()
                poolScene(i) to { beam(i, to()) }
            }
            row("beam", "beam-deleted-$where", "a `.beam` deleted under $where") {
                val i = pool()
                val b = beam(i, to())
                poolScene(i) to { delete(b) }
            }
            row("beam", "beam-rewritten-$where", "a `.beam` rewritten under $where") {
                val i = pool2()
                beam2(i, to())
                pool2Scene(i) to { beam2(i, to(), "v2") }
            }
        }
        row("beam", "beam-renamed", "the app's own `.beam` renamed") {
            val i = pool()
            val b = beam(i, ownBuild)
            poolScene(i) to { rename(b, "Renamed$i.txt") }
        }
        row("beam", "beam-moved-env", "a dep's `.beam` moved between envs") {
            val i = pool()
            val b = beam(i, depBuild)
            poolScene(i) to { move(b, vf(testDepBuild)) }
        }
        row("beam", "beam-moved-out", "a `.beam` moved out of every root") {
            val i = pool()
            val b = beam(i, ownBuild)
            poolScene(i) to { move(b, vf(File(dir, "outside"))) }
        }
        row("beam", "beam-name-mismatch-created", "a `.beam` whose file name isn't its module, created") {
            val i = pool()
            poolScene(i) to { beam(i, depBuild, name = "Elixir.Mismatch$i.beam") }
        }
        row("beam", "beam-name-mismatch-rewritten", "a `.beam` rewritten so that its module is another") {
            val i = pool()
            val j = pool()
            beam(i, depBuild, name = "Elixir.Mismatch$i.beam")
            scene(listOf(asker("HeldPool.N$i.f(1)")), listOf(poolAtom(i), poolAtom(j))) to {
                File(BEAMS, "v1/Elixir.HeldPool.N$j.beam").copyTo(File(depBuild, "Elixir.Mismatch$i.beam"), overwrite = true)
                vf(depBuild)
            }
        }
        row("beam", "erlang-beam-created", "`:erlang`-style name: a `.beam` created") {
            scene(listOf(asker(":held_erl_pool.f(1)")), listOf("held_erl_pool")) to {
                copy("erl/held_erl_pool.beam", depBuild, "held_erl_pool.beam")
            }
        }
        row("beam", "erlang-beam-deleted", "`:erlang`-style name: a `.beam` deleted") {
            val b = copy("erl/held_erl_pool.beam", depBuild, "held_erl_pool.beam")
            scene(listOf(asker(":held_erl_pool.f(1)")), listOf("held_erl_pool")) to { delete(b) }
        }
        row("beam", "erlang-source-created", "`:erlang`-style name: a source defining it") {
            scene(listOf(asker(":s_atom_$n.f()")), listOf("s_atom_$n")) to { src("a.ex", "defmodule :s_atom_$n do\n  def f, do: 1\nend\n") }
        }
    }

    /** The app's own build with no library root: in content, not a source root, so the index's scope has no copy here. */
    private fun plainOwnBeams() {
        row("beam", "beam-created-plain-own", "a `.beam` created under the app's own build, no library root", changes = false) {
            val i = pool()
            poolScene(i) to { beam(i, plainBuild) }
        }
        row("beam", "beam-deleted-plain-own", "a `.beam` deleted under the app's own build, no library root", changes = false) {
            val i = pool()
            val b = beam(i, plainBuild)
            poolScene(i) to { delete(b) }
        }
        row("beam", "beam-rewritten-plain-own", "a `.beam` rewritten under the app's own build, no library root", changes = false) {
            val i = pool2()
            beam2(i, plainBuild)
            pool2Scene(i) to { beam2(i, plainBuild, "v2") }
        }
    }

    private fun askers() {
        val created: Ctx.() -> Unit = { src("a.ex", mod("A")) }

        row("asker", "asker-completion-copy", "a new name, asked from a completion's copy") {
            val copy = ReadAction.computeBlocking<PsiFile, Throwable> { asker("$p.A.f()").copy() as PsiFile }
            scene(listOf(copy), listOf(atom("A"))) to created
        }
        row("asker", "asker-diff-file", "a new name, asked from a light file, as a diff's is") {
            val light = ReadAction.computeBlocking<PsiFile, Throwable> {
                PsiFileFactory.getInstance(project).createFileFromText("diff.ex", ElixirFileType.INSTANCE, "defmodule $p.Diff do\n  def run, do: $p.A.f()\nend\n")
            }
            scene(listOf(light), listOf(atom("A"))) to created
        }
        row("asker", "asker-injected", "a new name, asked from an injected fragment") {
            // A sigil hosts its template only with HTML injection on, through the injector the optional descriptor registers.
            val settings = ElixirExperimentalSettings.instance.state
            val wasOn = settings.enableHtmlInjection
            settings.enableHtmlInjection = true
            Disposer.register(disposable) { settings.enableHtmlInjection = wasOn }
            InjectedLanguageManager.getInstance(project).registerMultiHostInjector(ElixirSigilInjector(), disposable)
            val host = elixir(src("host.ex", "defmodule $p.Host do\n  import Phoenix.Component\n  def r(assigns), do: ~H\"\"\"\n  <div>{$p.A.f()}</div>\n  \"\"\"\nend\n"))
            // A dumb project runs no injector that isn't dumb-aware, and the file's new folder makes it dumb.
            settle()
            val injected = ReadAction.computeBlocking<PsiFile?, Throwable> {
                InjectedLanguageManager.getInstance(project).findInjectedElementAt(host, host.text.indexOf("<div>"))?.containingFile
            }
            scene(listOf(checkNotNull(injected) { "no fragment injected in host.ex" }), listOf(atom("A"))) to created
        }
        row("asker", "asker-lib-to-test", "the asker moved from source to test content, with a copy per env") {
            val i = pool2()
            beam2(i, ownBuild)
            beam2(i, testOwnBuild, "v2")
            val a = asker("HeldPool.R$i.f(1)")
            scene(listOf(a), listOf(pool2Atom(i))) to { move(a.virtualFile, folderOf("test/s$n")) }
        }
        row("asker", "asker-other-module", "the asker moved to another IDE module") {
            src("g.ex", mod("A"))
            src("h.ex", mod("A", "def h, do: 1"), under = "other/lib/s$n")
            val a = asker("$p.A.f()")
            scene(listOf(a), listOf(atom("A"))) to { move(a.virtualFile, folderOf("other/lib/s$n")) }
        }
    }

    private fun created() {
        row("created", "ex-new-name", "`.ex` created defining a new name, asked as `None`") {
            aScene(expanded = true) to { src("a.ex", mod("A")) }
        }
        row("created", "ex-second-copy", "`.ex` created defining a name with a copy: `Several`") {
            src("g.ex", mod("A"))
            aScene(expanded = true) to { src("a.ex", mod("A", "def g, do: 1")) }
        }
        row("created", "ex-refresh", "`.ex` created on disk, through a refresh") {
            aScene() to {
                disk { File(dir, folder).resolve("a.ex").writeText(mod("A")) }
            }
        }
        row("created", "exs-in-scope", "`.exs` created in the index's scope") {
            aScene() to { src("a.exs", mod("A")) }
        }
        row("created", "exs-out-of-scope", "`.exs` created out of the index's scope", changes = false) {
            aScene() to { src("a.exs", mod("A"), under = "outside/s$n") }
        }
        row("created", "ex-not-asked", "`.ex` created defining a name nobody asked", changes = false) {
            src("g.ex", mod("A"))
            aScene(expanded = true) to { src("b.ex", mod("B")) }
        }
    }

    private fun sourceFiles() {
        row("source", "deleted", "a source file deleted") {
            val f = src("a.ex", mod("A"))
            aScene(expanded = true) to { delete(f) }
        }
        row("source", "renamed", "a source file renamed") {
            val f = src("a.ex", mod("A"))
            aScene(expanded = true) to { rename(f, "b.ex") }
        }
        row("source", "moved-in-root", "a source file moved inside the source root") {
            val f = src("a.ex", mod("A"))
            aScene(expanded = true) to { move(f, folderOf("lib/s$n/sub")) }
        }
        row("source", "moved-out-of-root", "a source file moved out of the source root") {
            val f = src("a.ex", mod("A"))
            aScene(expanded = true) to { move(f, folderOf("outside/s$n")) }
        }
        row("source", "moved-into-root", "a source file moved into the source root") {
            val f = src("a.ex", mod("A"), under = "outside/s$n")
            aScene(expanded = true) to { move(f, folderOf(folder)) }
        }
        row("source", "dir-deleted", "a source directory deleted") {
            src("a.ex", mod("A"), under = "$folder/d")
            val d = folderOf("$folder/d")
            aScene(expanded = true) to { delete(d) }
        }
        row("source", "dir-renamed", "a source directory renamed") {
            src("a.ex", mod("A"), under = "$folder/d")
            val d = folderOf("$folder/d")
            aScene(expanded = true) to { rename(d, "e") }
        }
        row("source", "dir-moved-out", "a source directory moved out of the root") {
            src("a.ex", mod("A"), under = "$folder/d")
            val d = folderOf("$folder/d")
            aScene(expanded = true) to { move(d, folderOf("outside/s$n")) }
        }
        row("source", "dir-moved-in", "a directory holding a source moved into the root") {
            src("a.ex", mod("A"), under = "outside/s$n/d")
            val d = folderOf("outside/s$n/d")
            aScene(expanded = true) to { move(d, folderOf(folder)) }
        }
        row("source", "dir-created-refresh", "a directory holding a source created on disk, through a refresh") {
            aScene() to {
                disk {
                    File(dir, "$folder/d/e").mkdirs()
                    File(dir, "$folder/d/e/a.ex").writeText(mod("A"))
                }
            }
        }
        row("source", "dir-deleted-refresh", "a directory holding a source deleted on disk, through a refresh") {
            src("a.ex", mod("A"), under = "$folder/d/e")
            aScene() to {
                disk { File(dir, "$folder/d").deleteRecursively() }
            }
        }
    }

    private fun definitionForms() {
        row("form", "defprotocol", "`defprotocol` added") {
            val f = src("f.ex", "x = 1\n")
            scene(listOf(asker("$p.P.a(1)")), listOf(atom("P"))) to {
                route(Route.VFS_PSI, f, "defprotocol $p.P do\n  def a(x)\nend\n")
            }
        }
        row("form", "defimpl", "`defimpl` added") {
            src("p.ex", "defprotocol $p.P do\n  def a(x)\nend\n")
            val f = src("f.ex", "x = 1\n")
            scene(listOf(asker("$p.P.a(1)")), listOf(atom("P.Atom"))) to {
                route(Route.VFS_PSI, f, "defimpl $p.P, for: Atom do\n  def a(x), do: x\nend\n")
            }
        }
        row("form", "nested", "a nested `defmodule` added") {
            val f = src("f.ex", mod("A"))
            scene(listOf(asker("$p.A.N.f()")), listOf(atom("A.N"))) to {
                route(Route.VFS_PSI, f, "defmodule $p.A do\n  defmodule N do\n    def f, do: 1\n  end\nend\n")
            }
        }
        row("form", "run-time-name", "a module named only at run time", changes = false) {
            val f = src("f.ex", "x = 1\n")
            scene(listOf(asker("$p.R.f()")), listOf(atom("R"))) to {
                route(Route.VFS_PSI, f, "defmodule Module.concat([$p, System.get_env(\"R\")]) do\n  def f, do: 1\nend\n")
            }
        }
        row("form", "macro-defined", "a module a macro defines, which the index doesn't hold", changes = false) {
            src("mac.ex", "defmodule $p.Mac do\n  defmacro d do\n    quote do\n      defmodule $p.Made do\n        def f, do: 1\n      end\n    end\n  end\nend\n")
            val f = src("f.ex", "x = 1\n")
            scene(listOf(asker("$p.Made.f()")), listOf(atom("Made"))) to {
                route(Route.VFS_PSI, f, "defmodule $p.Using do\n  require $p.Mac\n  $p.Mac.d()\nend\n")
            }
        }
    }

    private fun filtered() {
        row("filtered", "filtered-source-gone", "the source a library `.beam` attaches, outside content, goes away") {
            val i = pool()
            beam(i, depBuild)
            val f = src("f$i.ex", onlySource(i), under = "deps/dep1/lib")
            poolScene(i) to { delete(f) }
        }
        row("filtered", "filtered-source-comes", "the source a library `.beam` attaches, outside content, comes") {
            val i = pool()
            beam(i, depBuild)
            poolScene(i) to {
                src("f$i.ex", onlySource(i), under = "deps/dep1/lib")
            }
        }
        /** A dep's `.beam` whose module two of its sources define, so it pairs with neither, and the first of them. */
        fun Ctx.twoSourcesScene(): Pair<VirtualFile, Scene> {
            val i = pool()
            beam(i, depBuild)
            val f = src("f$i.ex", onlySource(i), under = "deps/dep1/lib")
            src("g$i.ex", onlySource(i), under = "deps/dep1/lib")

            return f to poolScene(i)
        }
        row("filtered", "filtered-two-sources-one-rewritten", "two sources define a library `.beam`'s module, one rewritten on disk to define another") {
            val (f, scene) = twoSourcesScene()
            scene to { route(Route.VFS_NOPSI, f, "defmodule HeldPool.Other do\nend\n") }
        }
        row("filtered", "filtered-two-sources-one-renamed-txt", "two sources define a library `.beam`'s module, one with no PSI held renamed to `.txt`") {
            val (f, scene) = twoSourcesScene()
            scene to { noPsi(f); rename(f, "${f.nameWithoutExtension}.txt") }
        }
        /** A library `.beam` in content whose module a source in the IDE module the asker's depends on and a dep's source define. */
        fun Ctx.libraryBeamScene(): Pair<VirtualFile, Scene> {
            dependOnOther()
            val i = pool()
            val beam = beam(i, ownBuild)
            src("n$i.ex", onlySource(i), under = "other/lib")
            src("d$i.ex", onlySource(i), under = "deps/dep1/lib")

            return beam to poolScene(i)
        }
        row("filtered", "filtered-library-beam-gone", "a source in an IDE module the asker's depends on, a dep's source and a library `.beam` in content define one module; the `.beam` deleted") {
            val (beam, scene) = libraryBeamScene()
            scene to { delete(beam) }
        }
        row("filtered", "filtered-library-beam-renamed-txt", "as `filtered-library-beam-gone`, the `.beam` renamed to `.txt`") {
            val (beam, scene) = libraryBeamScene()
            scene to { rename(beam, "${beam.nameWithoutExtension}.txt") }
        }
        row("filtered", "filtered-env-gone","another env's `.beam` goes away", changes = false) {
            val i = pool2()
            beam2(i, depBuild)
            val other = beam2(i, testDepBuild, "v2")
            pool2Scene(i) to { delete(other) }
        }
        row("filtered", "filtered-env-comes", "another env's `.beam` comes", changes = false) {
            val i = pool2()
            beam2(i, depBuild)
            pool2Scene(i) to { beam2(i, testDepBuild, "v2") }
        }
        row("filtered", "filtered-consolidated-gone", "a consolidated copy goes away beside the own `.beam`", changes = false) {
            val i = pool2()
            beam2(i, ownBuild)
            val c = beam2(i, consolidated, "v2")
            pool2Scene(i) to { delete(c) }
        }
        row("filtered", "filtered-own-gone", "the own `.beam` goes away, leaving the consolidated copy") {
            val i = pool2()
            val own = beam2(i, ownBuild)
            beam2(i, consolidated, "v2")
            pool2Scene(i) to { delete(own) }
        }
    }

    private fun chains() {
        fun Ctx.using(name: String) =
            "defmodule $p.C do\n  defmacro __using__(_) do\n    quote do\n      def $name, do: 1\n    end\n  end\nend\n"
        for (how in listOf(Route.PSI, Route.SAVE, Route.VFS_NOPSI)) {
            val r = how.name.lowercase()
            row("chain", "chain-$r", "A reads B, B reads C, C's file changes, by $r") {
                val c = src("c.ex", using("from_c"))
                src("b.ex", "defmodule $p.B do\n  use $p.C\nend\n")
                scene(listOf(asker("$p.B.from_c()")), listOf(atom("B"), atom("C")), listOf(asker("$p.B.from_c()", "user.ex"))) to {
                    route(how, c, using("from_d"))
                }
            }
        }
        row("chain", "chain-in-progress", "A and B read each other, B's file changes: the in-progress branch") {
            val a = src("a.ex", "defmodule $p.A do\n  def f, do: 1\n  def g, do: $p.B.h()\nend\n")
            val b = src("b.ex", "defmodule $p.B do\n  require $p.A\n  def h, do: 1\nend\n")
            scene(listOf(elixir(a)), listOf(atom("A"), atom("B")), listOf(elixir(a), elixir(b))) to {
                route(Route.PSI, b, "defmodule $p.B do\n  require $p.A\n  def h2, do: 1\nend\n")
            }
        }
        /** `sub<n>`, whose config reads `Mod` by [read], written at [mod], and a [scene] of a reader of that config. */
        fun Ctx.modReadScene(mod: String, read: String = "$p.Mod.v()"): Pair<VirtualFile, Scene> {
            subProject()
            write("sub$n/config/config.exs", "import Config\n\nconfig :sub$n, k: $read\n")
            val m = write(mod, "defmodule $p.Mod do\n  def v, do: 1\nend\n")
            val reader = elixir(write("sub$n/lib/reader.ex", "defmodule $p.Reader do\n  if Application.compile_env(:sub$n, :k) do\n    def yes, do: 1\n  end\nend\n"))

            return m to scene(listOf(reader), listOf(atom("Mod")), listOf(reader))
        }
        for (how in listOf(Route.PSI, Route.VFS_PSI, Route.VFS_NOPSI)) {
            val r = how.name.lowercase()
            row("chain", "chain-reading-stops-$r", "a config file reads a module, whose file stops defining it, by $r: only `chosen` stamps it") {
                val (m, scene) = modReadScene("$folder/mod.ex")
                scene to { route(how, m, "defmodule $p.Other do\nend\n") }
            }
            row("chain", "chain-ensure-loaded-stops-$r", "a config file asks `Code.ensure_loaded?` of a module, whose file stops defining it, by $r") {
                val (m, scene) = modReadScene("$folder/mod.ex", "Code.ensure_loaded?($p.Mod)")
                scene to { route(how, m, "defmodule $p.Other do\nend\n") }
            }
        }
        row("chain", "chain-ensure-loaded-renamed-txt", "a config file asks `Code.ensure_loaded?` of a module, whose file is renamed to `.txt`") {
            val (m, scene) = modReadScene("$folder/mod.ex", "Code.ensure_loaded?($p.Mod)")
            scene to { noPsi(m); rename(m, "mod.txt") }
        }
        row("chain", "chain-reading","a config file reads a module: the reading branch", changes = false) {
            val (m, scene) = modReadScene("sub$n/lib/mod.ex")
            scene to { route(Route.PSI, m, "defmodule $p.Mod do\n  def w, do: 1\nend\n") }
        }
    }

    private fun project() {
        row("project", "folder-marked-excluded", "a folder marked excluded", control = Control.RECOMPUTED) {
            src("a.ex", mod("A"), under = "$folder/d")
            aScene() to {
                ModuleRootModificationUtil.updateModel(module) { it.contentEntries.first().addExcludeFolder(vf(File(dir, "$folder/d"))) }
            }
        }
        row("project", "dependency-added", "a module dependency added, as `resolveLoadable` reads the asker's scope", control = Control.RECOMPUTED) {
            val i = pool()
            val side = File(createTempDir("side-$n"), "ebin")
            VfsRootAccess.allowRootAccess(testRootDisposable, side.path)
            beam(i, side)
            poolScene(i) to {
                BeamLibraryFixture.addLibrary(project, module, "side-$n", listOf(vf(side)))
            }
        }
        row("project", "dumb-over-a-change", "dumb mode entered and left over a change") {
            val asker = asker("$p.A.f()")
            scene(listOf(asker), listOf(atom("A"))) to {
                DumbModeTestUtils.runInDumbModeSynchronously(project) {
                    src("a.ex", mod("A"))
                    read { attempt { "${ProjectModules(project).locate(asker, atom("A"), inputs)}" } }
                }
            }
        }
        row("project", "dumb-read", "a file created and a name read in dumb mode: every name moves", control = Control.RECOMPUTED) {
            aScene() to {
                DumbModeTestUtils.runInDumbModeSynchronously(project) {
                    src("a.ex", mod("A"))
                    read { ModuleNamesTracker.getInstance(project).modificationCount }
                }
            }
        }
        row("project", "cancelled", "a change made, a pass in smart mode cancelled, then the next pass") {
            aScene(expanded = true) to {
                src("a.ex", mod("A"))
                settle()
                val indicator = EmptyProgressIndicator()
                val cancelled = try {
                    ProgressManager.getInstance().runProcess({
                        indicator.cancel()
                        ReadAction.computeBlocking<Any?, Throwable> { ModuleNamesTracker.getInstance(project).modificationCount }
                    }, indicator)
                    false
                } catch (_: ProcessCanceledException) {
                    true
                }
                check(cancelled) { "the read was not cancelled" }
            }
        }
    }

    private fun negatives() {
        row("negative", "txt-anywhere", "a `.txt` anywhere", changes = false) {
            src("g.ex", mod("A"))
            aScene(expanded = true) to { src("note.txt", "x\n") }
        }
        row("negative", "txt-excluded-dep-build", "a `.txt` under a dep's excluded build", changes = false) {
            src("g.ex", mod("A"))
            aScene(expanded = true) to { write("_build/dev/lib/dep1/s$n.txt", "x\n") }
        }
        row("negative", "dir-created-empty", "an empty directory created, held by files reading no app env", changes = false) {
            src("g.ex", mod("A"))
            aScene(expanded = true) to { folderOf("$folder/empty") }
        }
        row("negative", "other-project", "a file created in another open project", changes = false) {
            src("g.ex", mod("A"))
            val root = createTempDir("second-$n")
            VfsRootAccess.allowRootAccess(testRootDisposable, root.path)
            val second = PlatformTestUtil.loadAndOpenProject(root.toPath(), disposable)
            val secondModule = WriteAction.computeAndWait<Module, Throwable> { ModuleManager.getInstance(second).newModule(File(root, "s.iml").toPath(), ModuleType.get(module).id) }
            ModuleRootModificationUtil.updateModel(secondModule) { it.addContentEntry(vf(root)).addSourceFolder(vf(root), false) }
            aScene(expanded = true) to {
                File(root, "other.ex").writeText(mod("A"))
                vf(root)
            }
        }
    }

    /** Each path an expansion reads, with a change that must reach it and one that needn't. */
    private fun sites() {
        fun Ctx.bodyAsker(name: String, body: String) = elixir(src(name, "defmodule $p.Reads do\n$body\nend\n"))

        /** A [scene] of `flagged/0`, defined when `File.exists?([path])`. */
        fun Ctx.flagScene(path: String = "Path.expand(\"flag\", __DIR__)"): Scene {
            val f = bodyAsker("e.ex", "  if File.exists?($path) do\n    def flagged, do: 1\n  end")

            return scene(listOf(asker("$p.Reads.flagged()")), listOf(atom("Reads")), listOf(f))
        }
        /** A [scene] of `first/0`, named from `__ENV__.file`, comparing the expansion of the file that defines it. */
        fun Ctx.envFileScene(): Scene {
            val f = bodyAsker("first.ex", "  def unquote(String.to_atom(Path.basename(__ENV__.file, \".ex\")))(), do: 1")

            return scene(listOf(asker("$p.Reads.first()")), listOf(atom("Reads")), listOf(f))
        }
        row("site", "site-file-renamed", "`__ENV__.file` read, the file renamed") {
            val envScene = envFileScene()
            envScene to { rename(envScene.expansions.single().virtualFile, "second.ex") }
        }
        row("site", "site-file-dir-renamed", "`__DIR__` read, its directory renamed") {
            val f = elixir(src("r.ex", "defmodule $p.Reads do\n  def unquote(String.to_atom(Path.basename(__DIR__)))(), do: 1\nend\n", under = "$folder/before"))
            scene(listOf(asker("$p.Reads.before()")), listOf(atom("Reads")), listOf(f)) to { rename(folderOf("$folder/before"), "later") }
        }
        row("site", "site-file-control", "`__ENV__.file` read, a sibling created", changes = false) {
            envFileScene() to { src("sibling.ex", mod("Sibling")) }
        }
        row("site", "site-exists-created", "`File.exists?` in a body, the path created") {
            flagScene() to { src("flag", "x\n") }
        }
        row("site", "site-exists-deleted", "`File.exists?` in a body, the path deleted") {
            val flag = src("flag", "x\n")
            flagScene() to { delete(flag) }
        }
        row("site", "site-exists-dotdot", "`File.exists?` by a path through `..`, the path created") {
            src("keep", "x\n", under = "$folder/sub")
            flagScene("__DIR__ <> \"/sub/../flag\"") to { src("flag", "x\n") }
        }
        row("site", "site-exists-parent-gone", "`File.exists?` true, the parent directory deleted") {
            src("flag", "x\n", under = "$folder/d")
            flagScene("Path.expand(\"d/flag\", __DIR__)") to { delete(folderOf("$folder/d")) }
        }
        if (IoTestUtil.isSymLinkCreationSupported) {
            row("site", "site-exists-symlink", "`File.exists?` through a symlink, the target's file created at its real path") {
                val real = File(dir, "outside/s$n/target").apply { mkdirs() }
                File(dir, folder).mkdirs()
                Files.createSymbolicLink(File(dir, "$folder/link").toPath(), real.toPath())
                vf(File(dir, folder))
                flagScene("Path.expand(\"link/flag\", __DIR__)") to { disk { File(real, "flag").writeText("x\n") } }
            }
        }
        row(
            "site",
            "site-exists-case",
            "`File.exists?` in a body, the path created spelled in another case",
            changes = !SystemInfoRt.isFileSystemCaseSensitive,
        ) {
            flagScene("Path.expand(\"FLAG\", __DIR__)") to { src("flag", "x\n") }
        }
        row("site", "site-exists-control", "`File.exists?` in a body, another path created", changes = false) {
            flagScene() to { src("other", "x\n") }
        }
        for ((kind, config) in listOf(
            "import" to "import Config\n\nconfig :SUB, k: 1\nif File.exists?(\"config/more.exs\") do\n  import_config \"more.exs\"\nend\n",
            "wildcard" to "use Mix.Config\n\nconfig :SUB, k: 1\nimport_config \"extra/*.exs\"\n",
        )) {
            /** A [subScene] of `sub<n>`, whose config reads by `kind`. */
            fun Ctx.configScene(): Scene {
                subProject()
                write("sub$n/config/config.exs", config.replace("SUB", "sub$n"))

                return subScene(subReader())
            }
            row("site", "site-config-$kind-created", "a config's $kind, its target created") {
                configScene() to {
                    subConfig(if (kind == "import") "sub$n/config/more.exs" else "sub$n/config/extra/a.exs")
                }
            }
            row("site", "site-config-$kind-control", "a config's $kind, a file created under `lib`", changes = false) {
                configScene() to {
                    write("sub$n/lib/more.ex", "defmodule $p.More do\nend\n")
                }
            }
        }
        row("site", "site-config-file-created", "the config file created, where there was none") {
            subProject()
            subScene(subReader()) to {
                subConfig()
            }
        }
        row("site", "site-config-file-deleted", "the config file deleted") {
            subProject()
            val c = subConfig()
            subScene(subReader()) to { delete(c) }
        }
        row("site", "site-mix-created-above", "a `mix.exs` created above a file, between it and its project") {
            subProject()
            subConfig()
            subScene(subReader("sub$n/inner/lib")) to {
                write("sub$n/inner/mix.exs", mixExs("Inner$n", "inner$n"))
            }
        }
        /** `dep1.app`, its env setting [key] to 1. */
        fun Ctx.dep1App(key: String) = write("_build/dev/lib/dep1/ebin/dep1.app", "{application,dep1,[{vsn,\"0.1.0\"},{env,[{$key,1}]}]}.\n")
        // Each answer `fetch_env` knows is an assumption, so the reader needs an `else` for a known and an unknown key to differ.
        fun Ctx.dep1Reader() = elixir(
            write(
                "deps/dep1/lib/reader$n.ex",
                "defmodule $p.Reader do\n  if Application.compile_env(:dep1, :k$n) == 1 do\n    def one, do: 1\n  else\n    def other, do: 1\n  end\nend\n",
            ),
        )
        row("site", "site-scope-config-file", "an `.exs` created in a config directory, read with no table") {
            dep1App("k$n")
            val reader = dep1Reader()
            scene(listOf(reader), listOf(atom("Reader")), listOf(reader)) to {
                write("config/more$n.exs", "import Config\n\nconfig :dep1, k$n: 2\n")
            }
        }
        row("site", "site-scope-mix-created", "a `mix.exs` created that makes an `.exs` a config file, which then reads app env as configured") {
            dep1App("k$n")
            write("sub$n/config/c.exs", "import Config\n\nconfig :dep1, k$n: 2\n")
            val reader = dep1Reader()
            scene(listOf(reader), listOf(atom("Reader")), listOf(reader)) to {
                write("sub$n/mix.exs", mixExs("Sub$n", "sub$n", ", config_path: \"config/c.exs\""))
            }
        }
        row("site", "site-scope-kept", "app env read with no table, a `.txt` created beside the reader", changes = false) {
            dep1App("k$n")
            val reader = dep1Reader()
            Scene(listOf(reader), listOf(atom("Reader")), emptyList(), reader) to { write("deps/dep1/lib/notes$n.txt", "x\n") }
        }
        row("site", "site-table-kept", "a config table read, a `.txt` created in its config directory", changes = false) {
            subProject()
            subConfig()
            val reader = subReader()
            Scene(listOf(asker("$p.Reader.two()")), listOf(atom("Reader")), emptyList(), reader) to { write("sub$n/config/notes.txt", "x\n") }
        }
        /** A [scene] of `one/0`, defined when `dep1`'s `:app_key<n>` is 1. */
        fun Ctx.appKeyScene(): Scene {
            val f = bodyAsker("e.ex", "  if Application.compile_env(:dep1, :app_key$n) == 1 do\n    def one, do: 1\n  end")

            return scene(listOf(asker("$p.Reads.one()")), listOf(atom("Reads")), listOf(f))
        }
        row("site", "site-app-file-created", "`<app>.app` created under a class root, read as app env") {
            appKeyScene() to {
                dep1App("app_key$n")
            }
        }
        row("site", "site-app-file-control", "`<app>.app` read as app env, a `.beam` created beside it", changes = false) {
            val i = pool()
            appKeyScene() to { beam(i, depBuild) }
        }
        row("site", "site-app-env-dir-created", "app env read, an empty directory created: `scope` and `appFile` are wide", changes = false, control = Control.RECOMPUTED) {
            val f = bodyAsker("e.ex", "  if Application.compile_env(:fix, :base) == 1 do\n    def one, do: 1\n  end")
            Scene(listOf(asker("$p.Reads.one()")), listOf(atom("Reads")), emptyList(), f) to { folderOf("$folder/empty") }
        }
        row("site", "site-build-env-dep", "a dep's config read through its one `_build` env, a second env for it created") {
            val f = elixir(write("deps/dep_$n/lib/r.ex", "defmodule $p.Reads do\n  if Application.compile_env(:fix, :base) == 1 do\n    def dev, do: 1\n  end\nend\n"))
            write("deps/dep_$n/mix.exs", mixExs("DepR$n", "dep_$n"))
            folderOf("_build/dev/lib/dep_$n")
            scene(listOf(f), listOf(atom("Reads")), listOf(f)) to { folderOf("_build/test/lib/dep_$n") }
        }
        row("site", "site-build-env-moved", "`Mix.env` read, the file moved from source to test content") {
            val f = bodyAsker("e.ex", "  if Mix.env() == :dev do\n    def dev, do: 1\n  end")
            scene(listOf(f), listOf(atom("Reads")), listOf(f)) to { move(f.virtualFile, folderOf("test/s$n")) }
        }
    }

    /** Last, as they take the project's build away and put it back. */
    private fun builds() {
        for ((what, path) in listOf("_build" to "_build", "ebin" to "_build/dev/lib/dep1/ebin", "env" to "_build/dev")) {
            row("build", "build-delete-$what", "$what deleted with no new event per file", control = Control.RECOMPUTED) {
                val i = pool()
                beam(i, depBuild)
                val saved = File(createTempDir("saved-$n"), "x")
                poolScene(i) to {
                    disk { Files.move(File(dir, path).toPath(), saved.toPath()) }
                    Disposer.register(disposable) { disk { Files.move(saved.toPath(), File(dir, path).toPath()) } }
                }
            }
            row("build", "build-restore-$what", "$what restored with no new event per file", control = Control.RECOMPUTED) {
                val i = pool()
                beam(i, depBuild)
                val saved = File(createTempDir("saved-$n"), "x")
                disk { Files.move(File(dir, path).toPath(), saved.toPath()) }
                poolScene(i) to {
                    disk { Files.move(saved.toPath(), File(dir, path).toPath()) }
                }
            }
        }
    }

    private companion object {
        /** Made by `generate.exs` beside them. */
        val BEAMS = File("testData/org/elixir_lang/expander/held_against_fresh")
    }
}
