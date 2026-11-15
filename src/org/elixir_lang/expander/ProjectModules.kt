package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangObject
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.impl.libraries.LibraryEx
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VirtualFileSystem
import com.intellij.psi.PsiCompiledFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.util.system.OS
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.expander.interpreter.Declined
import org.elixir_lang.expander.interpreter.Filename
import org.elixir_lang.expander.interpreter.equal
import org.elixir_lang.facet.FacetModels
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Lowering
import org.elixir_lang.mix.Project.MIX_EXS
import org.elixir_lang.mix.Project.depFolder
import org.elixir_lang.mix.Project.isDepsOfAMixProject
import org.elixir_lang.mix.ProjectKeywords
import org.elixir_lang.mix.buildEnv
import org.elixir_lang.mix.defaultBuildEnv
import org.elixir_lang.mix.library.Kind as MixLibraryKind
import org.elixir_lang.mix.sync.scopedDepLibraryName
import org.elixir_lang.mix.sync.scopedLibraryNameToken
import org.elixir_lang.model.psi.config.ConfigFiles
import org.elixir_lang.model.psi.config.ConfigSettings
import org.elixir_lang.navigation.isDecompiled
import org.elixir_lang.psi.ElementFactory
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.Import.Term
import org.elixir_lang.reference.Resolver
import org.elixir_lang.reference.resolver.Module
import org.jetbrains.annotations.TestOnly
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.PatternSyntaxException

/**
 * What each module exports and its struct, read from the copy Elixir loads for the file that asks: a `.beam`, or a
 * source file, which is read by expanding it. Answers are held, keyed by [VirtualFile], while every file and tracker
 * they read is unchanged.
 */
@Service(Service.Level.PROJECT)
internal class ProjectModules(private val project: Project) {
    /** What an expansion reads besides the project's files. An answer held for other inputs is recomputed. */
    data class Inputs(val level: ElixirLanguageLevel, val kernel: KernelImports, val os: OS)

    private val files = ByFile<Loaded>()
    private val beams = ByFile<Read>()

    /** The expansions [warm] reached, kept apart from their files' PSI, which is let go once no editor shows it. */
    private val warmed = ByFile<Held>()

    /** Module names' and paths' counts. */
    private val names: ModuleNamesTracker by lazy { ModuleNamesTracker.getInstance(project) }
    @Volatile
    private var clauseCache = ClauseCache<VirtualFile, Clauses>(CLAUSE_BOUND, Clauses::weight)

    /** An empty clause cache within [bound] bytes, or the usual bound where it is `null`. */
    @TestOnly
    internal fun boundClauses(bound: Long?) {
        clauseCache = ClauseCache(bound ?: CLAUSE_BOUND, Clauses::weight)
    }

    /** Per holder, so a new holder computes afresh. */
    private val held = Key.create<Held>("org.elixir_lang.expander.ProjectModules.held")
    private val chosen =
        Key.create<CachedValue<ConcurrentHashMap<String, HeldCopies>>>("org.elixir_lang.expander.ProjectModules.chosen")

    /** [atom]'s exports, from the copy Elixir loads for [asker]. */
    @RequiresReadLock
    fun exports(asker: PsiFile, atom: String, inputs: Inputs): ModuleExports =
        lookup(asker, atom, inputs, compiling(asker), Memo()).answer.exports

    /** [atom]'s struct, from the copy Elixir loads for [asker]. */
    @RequiresReadLock
    fun struct(asker: PsiFile, atom: String, inputs: Inputs): ModuleStruct =
        lookup(asker, atom, inputs, compiling(asker), Memo()).answer.struct

    /**
     * [atom]'s clauses, from the copy Elixir loads for [asker], as [exports] chooses it: none where which copy loads is
     * unknown, or where it doesn't load with readable exports. They are read when asked, and held apart from the answers
     * within their own bound.
     */
    @RequiresReadLock
    fun clauses(asker: PsiFile, atom: String, inputs: Inputs): ModuleClauses? =
        clauses(asker, atom, inputs, compiling(asker), Memo()).clauses

    /**
     * [atom]'s clauses for [asker], with the stamps of what was read for them, as [lookup] gives an answer's.
     */
    private fun clauses(
        asker: PsiFile,
        atom: String,
        inputs: Inputs,
        inProgress: Map<VirtualFile, Map<String, Answer>>,
        memo: Memo,
        reading: Boolean = false,
    ): ReadClauses {
        val stamps = Stamps()
        var transient = Transient.NONE
        val choice = chosen(asker, atom, stamps) { transient = it }

        fun read(choice: Choice): ModuleClauses? =
            when (choice) {
                Choice.Unknown, Choice.None, is Choice.Several -> null
                is Choice.One ->
                    when (val copy = choice.copy) {
                        is Copy.Compiled -> compiledClauses(copy.virtualFile, inputs.level)
                        is Copy.Source if copy.virtualFile in inProgress -> {
                            val running = runningAnswer(inProgress, copy.virtualFile, atom)
                            transient = Transient(mapOf(copy.virtualFile to mapOf(atom to running)), false)

                            if (running == null) choice.whileRunning()?.let(::read) else null
                        }
                        is Copy.Source if reading -> null
                        is Copy.Source -> {
                            val run = sourceClauses(copy, inputs, inProgress, memo)
                            transient = run.transient
                            stamps.addAll(run.loaded.stamps)

                            run.modules[atom] ?: if (run.loaded.answer(atom, choice.parents) == null) read(choice.fallingThrough()) else null
                        }
                    }
            }

        return ReadClauses(read(choice), stamps, transient)
    }

    /** Where Elixir loads a module from for a file that asks. */
    sealed interface Located {
        /** In dumb mode, where which copies there are is unknown. */
        data object Unknown : Located

        data object None : Located

        /** More than one copy, so which one loads is unknown. */
        class Several internal constructor(internal val each: List<Copy>) : Located {
            val copies: List<VirtualFile> get() = each.map { it.virtualFile }
        }

        /** @property source the source [beam]'s library or SDK attaches, which writes its definitions, `null` where none */
        class Compiled(val beam: VirtualFile, val exports: ModuleExports, val source: ElixirFile?) : Located

        /** @property result the module as [file]'s expansion compiled it, `null` where it didn't */
        class Source(val file: ElixirFile, val result: ExpansionResult?) : Located
    }

    /** The copy of [atom] Elixir loads for [asker], as [exports] and [struct] choose it. */
    @RequiresReadLock
    fun locate(asker: PsiFile, atom: String, inputs: Inputs): Located = located(asker, atom, choose(asker, atom)) { held(it, inputs) }!!

    /**
     * [locate]'s answer where it needs no expansion run: `null` where it would expand a source file whose expansion
     * isn't held, or isn't current.
     */
    @RequiresReadLock
    fun locateHeld(asker: PsiFile, atom: String, inputs: Inputs): Located? =
        located(asker, atom, choose(asker, atom)) { current(it, inputs) }

    /** [locate], keeping each source file's expansion it reaches, so that [locateHeld] finds it while it is current. */
    @RequiresReadLock
    fun warm(asker: PsiFile, atom: String, inputs: Inputs) {
        located(asker, atom, choose(asker, atom)) { file ->
            held(file, inputs).also { kept -> if (kept.transient.isNone) file.virtualFile?.let { warmed[it] = kept } }
        }
    }

    /**
     * Each of [several]'s copies of [atom] as [locate] answers one it alone holds: a `.beam`, or a source file's module.
     * A source file that leaves the name no module gives none, as the other copies hold it.
     */
    @RequiresReadLock
    fun locateEach(asker: PsiFile, several: Located.Several, atom: String, inputs: Inputs): List<Located> =
        several.each.map { located(asker, atom, Choice.One(it, emptyList())) { file -> held(file, inputs) }!! }.filter { it != Located.None }

    /** `null` where [hold] gives a source file no expansion. */
    private fun located(asker: PsiFile, atom: String, choice: Choice, hold: (ElixirFile) -> Held?): Located? =
        when (choice) {
            Choice.Unknown -> Located.Unknown
            Choice.None -> Located.None
            is Choice.Several -> Located.Several(choice.copies)
            is Choice.One ->
                when (val copy = choice.copy) {
                    is Copy.Compiled ->
                        Located.Compiled(copy.virtualFile, compiled(copy.virtualFile).exports, copy.source?.let { read(it) })
                    is Copy.Source -> {
                        val file = if (copy.virtualFile == fileOf(asker)) asker as? ElixirFile ?: copy.file else copy.file
                        val held = hold(file) ?: return null

                        if (held.loaded.answer(atom, choice.parents) == null) {
                            located(asker, atom, choice.fallingThrough(), hold)
                        } else {
                            Located.Source(file, held.results[atom])
                        }
                    }
                }
        }

    /** [file]'s expansion, against the modules the other files define, held on [file] while what it read is unchanged. */
    @RequiresReadLock
    fun expansion(file: ElixirFile, inputs: Inputs): FileExpansion = held(file, inputs).expansion

    /**
     * The env each node of [file]'s [expansion] was entered in, what each receiver it reached expanded to, and the
     * [Lowering] it read [file] through, held with it.
     */
    @RequiresReadLock
    fun indexed(file: ElixirFile, inputs: Inputs): Indexed =
        held(file, inputs).let { Indexed(it.envs!!, it.receivers!!, it.lowering) }

    class Indexed(val envs: EnvIndex, val receivers: ReceiverIndex, val lowering: Lowering)

    /** [file] expanded afresh, with [observer] told what the expansion reaches. Nothing is held. */
    @TestOnly
    @RequiresReadLock
    fun observe(file: ElixirFile, inputs: Inputs, observer: ExpansionObserver): FileExpansion =
        compute(file, inputs, emptyMap(), Memo(), observer = observer).expansion

    private fun held(file: ElixirFile, inputs: Inputs): Held {
        current(file, inputs)?.let { return it }

        val computed = compute(file, inputs, emptyMap(), Memo(), records = true)
        if (computed.transient.isNone) {
            file.virtualFile?.let {
                files[it] = computed.loaded
                if (warmed[it] != null) warmed[it] = computed
            }
            file.putUserData(held, computed)
        }

        return computed
    }

    /** [file]'s held expansion, or the one [warm] kept for it, where what it read is unchanged. */
    private fun current(file: ElixirFile, inputs: Inputs): Held? =
        (file.getUserData(held) ?: file.virtualFile?.let { warmed[it] })?.takeIf { it.loaded.isCurrent(inputs) }

    /** [asker]'s file as compiling, with none of its modules loaded yet. */
    private fun compiling(asker: PsiFile): Map<VirtualFile, Map<String, Answer>> =
        fileOf(asker)?.let { mapOf(it to emptyMap()) } ?: emptyMap()

    /** The file [file] is, or for a copy, as completion makes of the file being edited, the file it was made from. */
    private fun fileOf(file: PsiFile): VirtualFile? = file.originalFile.virtualFile

    /**
     * @param inProgress the source files whose expansions are running above this lookup, each with the modules its
     *   expansion has loaded so far
     * @param memo the values computed so far in this outermost call that hold only for some runs
     */
    private fun lookup(
        asker: PsiFile,
        atom: String,
        inputs: Inputs,
        inProgress: Map<VirtualFile, Map<String, Answer>>,
        memo: Memo,
        reading: Boolean = false,
    ): Looked {
        val stamps = Stamps()
        var transient = Transient.NONE
        val choice = chosen(asker, atom, stamps) { transient = it }

        fun answer(choice: Choice): Answer =
            when (choice) {
                Choice.None -> ABSENT
                Choice.Unknown, is Choice.Several -> UNREADABLE
                is Choice.One ->
                    when (val copy = choice.copy) {
                        is Copy.Compiled -> compiled(copy.virtualFile)
                        // Elixir loads each module as its own `defmodule` compiles, so one still compiling isn't loaded yet.
                        is Copy.Source if copy.virtualFile in inProgress -> {
                            val running = runningAnswer(inProgress, copy.virtualFile, atom)
                            transient = Transient(mapOf(copy.virtualFile to mapOf(atom to running)), false)

                            running ?: choice.whileRunning()?.let(::answer) ?: UNREADABLE
                        }
                        // Expanding the file here wouldn't be under the read, whose env and directory it may read.
                        is Copy.Source if reading -> UNREADABLE
                        is Copy.Source -> {
                            val (loaded, below) = loaded(copy, inputs, inProgress, memo)
                            transient = below
                            stamps.addAll(loaded.stamps)

                            loaded.answer(atom, choice.parents) ?: answer(choice.fallingThrough())
                        }
                    }
            }

        return Looked(answer(choice), stamps, transient)
    }

    /**
     * [choose]'s choice, with [stamps] gaining those that move when it may: none where it is unknown, which holds only
     * while the index is dumb ([transient]), else its entry's and each of several copies' own. A source copy's content
     * stamps are its expansion's.
     */
    private fun chosen(asker: PsiFile, atom: String, stamps: Stamps, transient: (Transient) -> Unit): Choice {
        val choice = choose(asker, atom, stamps)
        if (choice is Choice.Several) choice.copies.forEach { stamps.copy(it) }
        if (choice == Choice.Unknown) transient(Transient.DUMB)

        return choice
    }

    /**
     * Which of [atom]'s copies Elixir loads for [asker]: unknown in dumb mode, else none, several or the one. A name no
     * copy holds is looked for in the file of its nearest parent name ([parentsFile]). [into] takes the stamps of each
     * entry read ([heldCopies]).
     */
    private fun choose(asker: PsiFile, atom: String, into: Stamps? = null): Choice {
        if (DumbService.isDumb(project)) return Choice.Unknown
        val held = heldCopies(asker, atom)
        into?.addAll(held.stamps)
        val chosen = chosenCopies(held)
        // The `.beam`s of the libraries and the SDK outside the project's content not among them.
        val fallback = held.fallback.map(::compiledCopy)

        return when (val copy = chosen.singleOrNull()) {
            null -> if (chosen.isEmpty()) parentsFile(asker, atom, fallback, into) ?: Choice.None else Choice.Several(chosen)
            else -> Choice.One(copy, fallback)
        }
    }

    /**
     * The source file of [atom]'s nearest parent name that one source file defines, as the copy that may create [atom]
     * under a name it computes, as a router creates its `Helpers`: `Elixir.A.B.C`'s `Elixir.A.B`, then `Elixir.A`.
     * Where that is [asker]'s own file, whose run decides what its code may have created ([Run.maybeCreated]), the name
     * falls through as any name a file leaves no module does. Several where the nearest parent has several copies, as
     * any may be the one that ran; `null` where no file defines a parent. [into] takes the stamps of each parent's
     * entry.
     */
    private fun parentsFile(asker: PsiFile, atom: String, fallback: List<Copy.Compiled>, into: Stamps?): Choice? {
        var parent = atom.substringBeforeLast('.', "")

        while (parent.contains('.')) {
            val held = heldCopies(asker, parent)
            into?.addAll(held.stamps)
            val chosen = chosenCopies(held)
            if (chosen.size > 1) return Choice.Several(chosen)
            (chosen.singleOrNull() as? Copy.Source)?.let { file ->
                val parents = Choice.One(file, fallback, parents = true)

                return if (file.virtualFile == fileOf(asker)) parents.fallingThrough() else parents
            }
            parent = parent.substringBeforeLast('.', "")
        }

        return null
    }

    private sealed interface Choice {
        data object Unknown : Choice
        data object None : Choice
        class Several(val copies: List<Copy>) : Choice

        /**
         * @property fallback the `.beam`s of the libraries and the SDK outside the project's content, which Elixir loads
         *   from the code path where [copy], a source file, defines no module under the name after all
         * @property parents whether [copy] is the file of the name's nearest parent ([parentsFile]), where no index
         *   names the module
         */
        class One(val copy: Copy, val fallback: List<Copy.Compiled>, val parents: Boolean = false) : Choice {
            /** Where Elixir loads the name from once [copy], a source file, leaves it no module: the code path's `.beam`s, as [choose] reads copies. */
            fun fallingThrough(): Choice =
                when (val beam = fallback.singleOrNull()) {
                    null -> if (fallback.isEmpty()) None else Several(fallback)
                    else -> One(beam, emptyList())
                }

            /**
             * Where Elixir loads the name from while [copy], a source file, still runs and hasn't loaded it:
             * [fallingThrough] where an index names the module there, else unknown, `null`.
             */
            fun whileRunning(): Choice? = if (parents) null else fallingThrough()
        }
    }

    /** The copies of [held]'s name Elixir prefers for its asker. A reparse replaces a file's PSI, so each source copy's PSI is found again. */
    private fun chosenCopies(held: HeldCopies): List<Copy> =
        held.chosen.mapNotNull { copy ->
            if (copy.compiled) {
                compiledCopy(copy)
            } else {
                read(copy.virtualFile)?.let { Copy.Source(it, copy.virtualFile) }
            }
        }

    private fun compiledCopy(copy: HeldCopy): Copy.Compiled = Copy.Compiled(copy.virtualFile, copy.source)

    /** A file added, moved or deleted, a module name defined or dropped, a root. */
    fun copyTrackers(): Array<Any> =
        arrayOf(
            VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
            names,
            ProjectRootManager.getInstance(project),
        )

    /**
     * Held per asker while the roots and the asker's path hold, as they say which copies load for it, and each entry while
     * its name, the roots and, on disk, every copy it is ruled from hold.
     */
    private fun heldCopies(asker: PsiFile, atom: String): HeldCopies {
        val byAtom = CachedValuesManager.getManager(project).getCachedValue(
            asker,
            chosen,
            {
                CachedValueProvider.Result.create(
                    ConcurrentHashMap<String, HeldCopies>(),
                    ProjectRootManager.getInstance(project),
                    names.askerPath(fileOf(asker)),
                )
            },
            false,
        )
        byAtom[atom]?.takeIf { it.stamps.isCurrent() }?.let { return it }

        val stamps = Stamps()
        stamps.track(names.name(atom))
        stamps.track(ProjectRootManager.getInstance(project))
        val loadable = Module.resolveLoadable(asker, org.elixir_lang.Module.indexName(atom))
        val chosen = Resolver.preferred(asker, false, loadable.loaded).mapNotNull { copy(it.element) }.distinctBy { it.virtualFile }
        val chosenFiles = chosen.mapTo(HashSet()) { it.virtualFile }
        val fallback = loadable.loaded.mapNotNull { copy(it.element) }
            .filter { it.compiled && it.virtualFile !in chosenFiles && isDependencyOutput(it.virtualFile) }
            .distinctBy { it.virtualFile }
        // Each copy the choice is ruled from, kept or not, and so each source a `.beam` may pair with: a deletion, or a
        // rename or a rewrite with no tree loaded, moves no name.
        loadable.files.forEach(stamps::onDisk)
        val paired = { copy: HeldCopy ->
            if (copy.compiled) HeldCopy(copy.virtualFile, true, loadable.sourceOf(copy.virtualFile)) else copy
        }
        val copies = HeldCopies(chosen.map(paired), fallback.map(paired), stamps)
        byAtom[atom] = copies

        return copies
    }

    private fun compiled(beam: VirtualFile): Answer {
        val stamp = beam.modificationStamp
        beams[beam]?.takeIf { it.stamp == stamp }?.let { return it.answer }

        val bytes = try {
            beam.contentsToByteArray()
        } catch (_: IOException) {
            return UNREADABLE
        }
        val exports = LoadedModules.exports(bytes, beam.path)
        val answer = Answer(exports, LoadedModules.struct(exports) { bytes to beam.path })
        beams[beam] = Read(stamp, answer)

        return answer
    }

    /** The Elixir [file] a module's clauses are read from: a source file, or a `.beam`'s decompiled mirror. */
    @RequiresBackgroundThread(generateAssertion = false)
    @RequiresReadLock
    fun read(file: VirtualFile): ElixirFile? {
        if (!file.isValid) return null
        val psi = PsiManager.getInstance(project).findFile(file)

        return ((psi as? PsiCompiledFile)?.decompiledPsiFile ?: psi) as? ElixirFile
    }

    private fun compiledClauses(beam: VirtualFile, level: ElixirLanguageLevel): ModuleClauses? {
        val stamp = beam.modificationStamp
        (clauseCache[beam] as? Clauses.Compiled)?.takeIf { it.stamp == stamp && it.level == level }?.let { return it.clauses }

        val deprecations = (compiled(beam).exports as? ModuleExports.Present)?.deprecated ?: return null
        val bytes = try {
            beam.contentsToByteArray()
        } catch (_: IOException) {
            return null
        }
        val mirror = read(beam) ?: return null
        val clauses = CompiledModule.of(bytes, beam.path, mirror, level, deprecations)?.let { ModuleClauses.of(it, beam) }
        clauseCache[beam] = Clauses.Compiled(stamp, level, clauses)

        return clauses
    }

    /** The clauses of each module [copy] loads, with the expansion that compiled them. */
    private fun sourceClauses(
        copy: Copy.Source,
        inputs: Inputs,
        inProgress: Map<VirtualFile, Map<String, Answer>>,
        memo: Memo,
    ): SourceRun {
        (clauseCache[copy.virtualFile] as? Clauses.Source)
            ?.takeIf { it.loaded.isCurrent(inputs) && it.loaded.holdsWhile(inProgress) }
            ?.let { return SourceRun(it, Transient.NONE) }

        return expanded(copy, inputs, inProgress, memo)
    }

    /** An observer putting each module's clauses, as it loads, into [modules], or taking them out where it has none. */
    private fun clausesInto(modules: MutableMap<String, ModuleClauses>) =
        object : ExpansionObserver {
            override fun entering(node: ElixirAst, state: ExState, env: Env) {}

            override fun clausesLoaded(module: String, clauses: ModuleClauses?) {
                if (clauses == null) modules.remove(module) else modules[module] = clauses
            }
        }

    private fun copy(element: PsiElement): HeldCopy? {
        val file = element.containingFile ?: return null
        val virtualFile = fileOf(file) ?: return null

        return when {
            element.isDecompiled() -> HeldCopy(virtualFile, compiled = true)
            file.originalFile is ElixirFile -> HeldCopy(virtualFile, compiled = false)
            else -> null
        }
    }

    /** [copy]'s modules, and what they read that holds only for this run. */
    private fun loaded(
        copy: Copy.Source,
        inputs: Inputs,
        inProgress: Map<VirtualFile, Map<String, Answer>>,
        memo: Memo,
    ): Pair<Loaded, Transient> {
        // A stored value read the files in progress here as loaded, where this run must read them as compiling.
        files[copy.virtualFile]
            ?.takeIf { it.isCurrent(inputs) && it.holdsWhile(inProgress) }
            ?.let { return it to Transient.NONE }

        return expanded(copy, inputs, inProgress, memo).let { it.loaded to it.transient }
    }

    /**
     * [copy] expanded, from [memo] where this call already expanded it under [inProgress]. The clauses it loads are kept
     * with it, so a later read of them doesn't expand the file again: held where nothing it read holds only for this
     * run, and else in [memo].
     */
    private fun expanded(copy: Copy.Source, inputs: Inputs, inProgress: Map<VirtualFile, Map<String, Answer>>, memo: Memo): SourceRun {
        memo.of(copy.virtualFile, inProgress)?.let { return it }

        val modules = HashMap<String, ModuleClauses>()
        val computed = compute(copy.file, inputs, inProgress, memo, observer = clausesInto(modules))
        val run = SourceRun(Clauses.Source(computed.loaded, modules), computed.transient)
        if (computed.transient.isNone) {
            files[copy.virtualFile] = computed.loaded
            clauseCache[copy.virtualFile] = run.clauses
        } else if (!computed.transient.dumb) {
            memo.add(copy.virtualFile, run)
        }

        return run
    }

    /** A source file's expansion: the modules it loaded with their [clauses], and what it read that holds only for its run. */
    private class SourceRun(val clauses: Clauses.Source, val transient: Transient) {
        val loaded: Loaded get() = clauses.loaded
        val modules: Map<String, ModuleClauses> get() = clauses.modules
    }

    /**
     * Only a computation that completes is stored, so a cancelled one leaves nothing behind.
     *
     * @param records whether the env each node is entered in is recorded, which only the file asked about needs
     */
    private fun compute(
        file: ElixirFile,
        inputs: Inputs,
        inProgress: Map<VirtualFile, Map<String, Answer>>,
        memo: Memo,
        records: Boolean = false,
        observer: ExpansionObserver = ExpansionObserver.NONE,
        config: ConfigRead? = null,
    ): Held {
        val virtualFile = file.virtualFile
        val stamps = Stamps()
        if (virtualFile != null) stamps.source(virtualFile) else stamps.own(file)
        val modules = HashMap<String, Answer>()
        val results = HashMap<String, ExpansionResult>()
        val compiling = fileOf(file)
        val buildTarget = buildTarget(compiling, config, stamps)
        val within = compiling?.let { inProgress + (it to modules) } ?: inProgress
        val looked = HashMap<String, Looked>()
        // A re-read answer joins the one it supersedes, as the expansion used both.
        val used = ArrayList<Looked>()
        val transients = ArrayList<Transient>()
        // The config read the file's build runs after, whose follows loaded modules the file then sees. A read reads none.
        val buildRead by lazy(LazyThreadSafetyMode.NONE) {
            if (config != null) null else readOf(virtualFile, buildTarget, inputs, stamps) { transients += it }
        }
        // Which copy the index holds of each module the read followed, asked once for its exports, struct and clauses.
        val copies = HashMap<String, Boolean?>()
        // The clauses of each module the read followed, read once for every call into it.
        val followedClauses = HashMap<String, ModuleClauses?>()
        fun seenFromRead(atom: String, withClauses: Boolean): FollowedLoad? {
            val read = buildRead ?: return null
            val followed = read.table.followed[atom] ?: return null
            val clauses = when {
                !withClauses -> null
                atom in followedClauses -> followedClauses[atom]
                else -> readClauses(read, atom, stamps) { transients += it }.also { followedClauses[atom] = it }
            }
            val copy = if (atom in copies) {
                copies[atom]
            } else {
                indexCopy(chosen(file, atom, stamps) { transients += it }).also { copies[atom] = it }
            }

            return seenAfterFollow(FollowedLoad(followed, clauses), copy, inputs.level, FollowedBy.READ)
        }
        val ask = { atom: String ->
            seenFromRead(atom, false)?.module?.let { Answer(it.exports, it.struct) }
                ?: looked[atom]
                    ?.takeIf { it.transient.holdsWhile(within) }
                    ?.answer
                ?: lookup(file, atom, inputs, within, memo, config != null).also {
                    looked[atom] = it
                    used += it
                }.answer
        }
        val top = Env.empty(inputs.level, inputs.kernel)
        val envs = if (records) EnvIndex(top) else null
        val receivers = if (records) ReceiverIndex() else null
        val lowering = Lowering.of(file, inputs.level)
        val expansion = Expander.expandFile(
            lowering.lower(file).let { if (file.isDecompiled()) compilerOutput(it) { writtenDefinitions(file) } else it },
            top,
            inputs.level,
            { ask(it).exports },
            { ask(it).struct },
            appEnv(inputs, stamps, virtualFile, file, { buildRead }, config, inProgress) { transients += it },
            object : ExpansionObserver by observer {
                override fun entering(node: ElixirAst, state: ExState, env: Env) {
                    envs?.entering(node, env)
                    observer.entering(node, state, env)
                }

                override fun left(node: ElixirAst, expansion: Expansion) {
                    envs?.left(node, expansion)
                    observer.left(node, expansion)
                }

                override fun uncompiled(defmodule: ElixirAst, body: ElixirAst) {
                    envs?.uncompiled(defmodule, body)
                    observer.uncompiled(defmodule, body)
                }

                override fun receiver(qualifier: ElixirAst, value: Term) {
                    receivers?.expanded(qualifier, value)
                    observer.receiver(qualifier, value)
                }

                override fun loaded(result: ExpansionResult, exports: ModuleExports, struct: ModuleStruct) {
                    // A module that loads as absent, as one whose body raised does, leaves the name no module of this
                    // file's and nothing for another to reach.
                    if (exports == ModuleExports.Absent) {
                        modules.remove(result.module)
                        results.remove(result.module)
                    } else {
                        modules[result.module] = Answer(exports, struct)
                        results[result.module] = result
                    }
                    observer.loaded(result, exports, struct)
                }

                override fun reached(module: FollowedModule) {
                    modules[module.name] = Answer(module.exports, module.struct)
                    observer.reached(module)
                }
            },
            file = {
                // A rename or move discards this run's answers.
                stamps.track(names.askerPath(virtualFile))
                runPath(virtualFile, inputs.os, OS.CURRENT)
            },
            virtualFile = virtualFile,
            os = inputs.os,
            buildTarget = buildTarget,
            config = config,
            files = runFiles(virtualFile, inputs, within, memo, stamps, config != null, { buildRead }) { transients += it },
            directories = { path ->
                // A `mix.exs` created or deleted above the file moves its answer.
                stamps.track(names.above(path))
                virtualFile?.fileSystem?.findFileByPath(path)?.let(::compileDirectory)
            },
            clauses = { atom ->
                val seen = seenFromRead(atom, true)

                if (seen != null) {
                    seen.clauses
                } else {
                    clauses(file, atom, inputs, within, memo, config != null).also { read ->
                        stamps.addAll(read.stamps)
                        transients += read.transient
                    }.clauses
                }
            },
        )
        used.forEach { stamps.addAll(it.stamps) }

        val uncompiled = LoadedModules.uncompiledExports(expansion)?.let { Answer(it, ModuleStruct.Unreadable) }
        val transient = Transient.merged(transients + used.map(Looked::transient)).leaving(compiling)

        val loaded = Loaded(inputs, modules, uncompiled, expansion.unfollowedRan, stamps, followedBy(expansion, modules))

        return Held(loaded, expansion, results, lowering, transient, envs, receivers)
    }

    /** The modules [expansion]'s follows loaded, of those its run [loaded]: each that isn't its own, at its top or nested. */
    private fun followedBy(expansion: FileExpansion, loaded: Map<String, Answer>): List<FollowedModule> {
        val own = results(expansion).mapTo(HashSet()) { it.module }

        return loaded.filterKeys { it !in own }.map { (name, answer) -> FollowedModule(name, answer.exports, answer.struct) }
    }

    /** The app env a default build of the project sees, for an expansion that keeps no stamps, of the `.app` files [asker] loads. */
    @RequiresReadLock
    fun appEnv(inputs: Inputs, asker: PsiFile): AppEnv = appEnv(inputs, Stamps(), null, asker, { null }, null, emptyMap()) {}

    /**
     * The app env a default build of the project sees: the config table [file]'s build reads where its read declined
     * nothing ([ConfigTable.of]), else, where there is no table or it did, as [AppEnv.of] orders it: a key a config file
     * that counts sets, or may set where one of its settings can't be read. Past either, what the app's `.app` file
     * declares, read from a class root of the libraries and the SDK outside the project's content, as the `.beam`
     * fallback is. The project's own apps are built from the sources being expanded, so their `.app` files aren't read. A
     * read made expanding [file], a config file that counts or one a config [read] evaluates, is configured: Mix loads the
     * config before the dependencies' `.app` files. So is one made while a config file's expansion runs above [file]'s
     * ([inProgress]), which holds only while it runs. [stamps] gain what each answer read, and [transient] what holds
     * only for this run: in dumb mode nothing can be read, and each answer is unreadable. Where an app has several
     * `.app` copies, [asker] chooses among them as it does among a module's `.beam`s ([Module.loads]); it moves no other
     * answer.
     */
    private fun appEnv(
        inputs: Inputs,
        stamps: Stamps,
        file: VirtualFile?,
        asker: PsiFile,
        buildRead: () -> BuildRead?,
        read: ConfigRead?,
        inProgress: Map<VirtualFile, Map<String, Answer>>,
        transient: (Transient) -> Unit,
    ): AppEnv {
        val level = inputs.level
        val configFiles = ArrayList<VirtualFile>()
        val trackedMixExs by lazy { trackMixExs(stamps) }
        // Which files are config files: a `mix.exs` names its config directory.
        val scope by lazy {
            trackedMixExs

            ConfigFiles.scope(project)
        }
        val config by lazy {
            scope.also { scope ->
                configFiles += FilenameIndex.getAllFilesByExt(project, "exs", scope)
                configFiles.forEach(stamps::source)
            }
        }
        val declarations = HashMap<String, AppDeclaration>()

        // Whether this file is expanded within a config file's expansion.
        fun configRunning(): Boolean {
            val running = inProgress.keys.filter(scope::contains)
            if (running.isNotEmpty()) transient(Transient(running.associateWith { emptyMap() }, false))

            return running.isNotEmpty()
        }

        val declaration = { app: String -> declarations.getOrPut(app) { appFile(app, level, asker, stamps) { trackedMixExs; mayHave(app) } } }
        val env = AppEnv.of(
            { app, key -> (key as? OtpErlangAtom)?.let { ConfigSettings.of(project, app, it.atomValue(), config).isNotEmpty() } == true },
            { app -> ConfigSettings.unread(project, app, config) },
            declaration,
        )
        return AppEnv { app, key ->
            when {
                DumbService.isDumb(project) -> {
                    transient(Transient.DUMB)
                    AppEnvValue.Unreadable
                }
                read != null || file != null && scope.contains(file) || configRunning() -> AppEnvValue.Configured
                else -> buildRead()?.table?.takeUnless { it.declined }?.let { table ->
                    table.of(app, key) ?: AppEnv.declared(declaration(app), key)
                } ?: env.of(app, key).also {
                    // Whether a config file's call sets or writes a key rests on the module its expansion names, so on
                    // what that expansion read.
                    configFiles.forEach { file -> files[file]?.stamps?.takeIf { it.isCurrent() }?.let(stamps::addAll) }
                }
            }
        }
    }

    /**
     * The config table [file]'s build reads under [env], from the config path of the innermost content `mix.exs` above
     * it, `null` where that path isn't known.
     */
    @TestOnly
    @RequiresReadLock
    fun configTable(file: VirtualFile, inputs: Inputs, env: String): ConfigTable? =
        buildRead(file, env, BuildTarget.HOST, inputs, Stamps()) {}?.table

    /**
     * The config files the `:dev`, `:test` and `:prod` reads of each content `mix.exs` import, its config file included.
     * Only this asks for `:prod`. A file only a unit a read declined may have imported isn't among them.
     */
    @RequiresReadLock
    fun reachedConfigFiles(inputs: Inputs): Set<VirtualFile> =
        ConfigFiles.projects(project).flatMapTo(HashSet()) { (mixExs, entry) ->
            REACHED_ENVS
                .flatMap { env -> buildRead(mixExs, entry.configPath, env, BuildTarget.HOST, inputs, Stamps()) {}?.table?.files.orEmpty() }
                .mapNotNull(mixExs.fileSystem::findFileByPath)
        }

    /** The config read [file]'s build makes under [env], from the innermost content `mix.exs` above it. */
    private fun buildRead(
        file: VirtualFile,
        env: String,
        target: String,
        inputs: Inputs,
        stamps: Stamps,
        transient: (Transient) -> Unit,
    ): BuildRead? {
        // A `mix.exs` created or deleted above the file moves which project's config it reads.
        stamps.track(names.above(file.path))
        val (mixExs, configPath) = generateSequence(file.parent) { it.parent }
            .mapNotNull { it.findChild(MIX_EXS) }
            .firstNotNullOfOrNull { mixExs -> ConfigFiles.configPath(project, mixExs)?.let { mixExs to it } }
            ?: return null

        return buildRead(mixExs, configPath, env, target, inputs, stamps, transient)
    }

    private fun buildRead(
        mixExs: VirtualFile,
        configPath: ProjectKeywords.Reading<String>,
        env: String,
        target: String,
        inputs: Inputs,
        stamps: Stamps,
        transient: (Transient) -> Unit,
    ): BuildRead? {
        stamps.source(mixExs)
        val written = configPath.written ?: return null
        val path = ConfigFiles.configFilePath(mixExs, written)
        // Where it is missing, empty for the default path, as `Mix.Tasks.Loadconfig` then reads none, and else unknown, as it
        // raises. That reads how `mix.exs` wrote the path, so it isn't held by the path.
        val configFile = stamps.find(mixExs.fileSystem, path)?.takeUnless { it.isDirectory }
            ?: return BuildRead(if (ProjectKeywords.isDefault(written)) ConfigTable.EMPTY else ConfigTable.UNKNOWN)
        val source = ReadSource(TableKey(path, mixExs.parent.path, env, target, inputs), configFile)

        tables[source.key]?.takeIf { it.stamps.isCurrent() }?.let { held ->
            stamps.addAll(held.stamps)

            return BuildRead(held.table, source)
        }

        return evaluated(source, stamps, transient)
    }

    /**
     * The read at [source], evaluated: held in [tables] where nothing it read holds only for this run, with the clauses
     * of the modules its follows loaded in [clauseCache] beside it.
     */
    private fun evaluated(source: ReadSource, stamps: Stamps, transient: (Transient) -> Unit): BuildRead {
        val key = source.key
        val held = Stamps()
        val transients = ArrayList<Transient>()
        val read = ConfigRead(key.env, key.target, key.directory)
        val table = evaluateConfig(source.configFile, read, key.inputs, held) { transients += it }
        val merged = Transient.merged(transients)
        val clauses = read.followedClauses()

        stamps.addAll(held)
        if (merged.isNone) {
            tables.values.removeIf { !it.stamps.isCurrent() }
            tables[key] = HeldTable(table, held)
            if (table.followed.isNotEmpty()) {
                val reads = (clauseCache[source.configFile] as? Clauses.Read)?.reads.orEmpty().filterValues { it.stamps.isCurrent() }
                clauseCache[source.configFile] = Clauses.Read(reads + (key to Clauses.ReadModules(held, clauses)))
            }
        } else {
            transient(merged)
        }

        return BuildRead(table, source, clauses)
    }

    /**
     * The clauses of [module], which [read]'s follows loaded: the read's own where it was just evaluated, else its
     * [Clauses.Read] entry. Where that was evicted, the read's table is dropped and the read evaluated again.
     */
    private fun readClauses(read: BuildRead, module: String, stamps: Stamps, transient: (Transient) -> Unit): ModuleClauses? {
        read.clauses?.let { return it[module] }
        val source = read.source ?: return null

        (clauseCache[source.configFile] as? Clauses.Read)?.reads?.get(source.key)
            ?.takeIf { it.stamps.isCurrent() }
            ?.let { return it.modules[module] }
        tables.remove(source.key)

        return evaluated(source, stamps, transient).clauses?.get(module)
    }

    /** [configFile] read as `Config.Reader.read_imports!/2` reads it. */
    private fun evaluateConfig(
        configFile: VirtualFile,
        read: ConfigRead,
        inputs: Inputs,
        stamps: Stamps,
        transient: (Transient) -> Unit,
    ): ConfigTable {
        // The file created or deleted moves the table.
        stamps.track(names.path(configFile.path))
        val psi = read(configFile) ?: return ConfigTable.UNKNOWN
        read.imports.add(runPath(configFile, inputs.os, OS.CURRENT) ?: configFile.path)
        val computed = compute(psi, inputs, emptyMap(), Memo(), config = read)
        stamps.addAll(computed.loaded.stamps)
        transient(computed.transient)

        return when (computed.expansion.top) {
            is Expansion.Error -> read.raised()
            is Expansion.Expanded -> read.table(inputs.level)
            else -> {
                read.unitDeclined()
                read.table(inputs.level)
            }
        }
    }

    private val tables = ConcurrentHashMap<TableKey, HeldTable>()

    private data class TableKey(val configPath: String, val directory: String, val env: String, val target: String, val inputs: Inputs)

    /** A config table with the stamps of every file, existence check and import its read made. */
    private class HeldTable(val table: ConfigTable, val stamps: Stamps)

    /** Where a build's config read evaluates: the table's [key] in [tables], and the root [configFile]. */
    private class ReadSource(val key: TableKey, val configFile: VirtualFile)

    /**
     * What a build's config read left: its [table], and where it evaluated a file, the [source] it read; [clauses], the
     * clauses of the modules its follows loaded, where it was evaluated for this ask.
     */
    private class BuildRead(val table: ConfigTable, val source: ReadSource? = null, val clauses: Map<String, ModuleClauses>? = null)

    /** What the config read of [build], the build [file] is in, left, for a file outside a read. An unknown env has no read. */
    private fun readOf(file: VirtualFile?, build: BuildTarget?, inputs: Inputs, stamps: Stamps, transient: (Transient) -> Unit): BuildRead? {
        val env = build?.configEnv ?: return null

        return file?.let { buildRead(it, env, build.target, inputs, stamps, transient) }
    }

    /**
     * Which copy of a module the index holds, where it [chose][chosen] [choice], as [RunFiles.copyOf] gives it. A dumb
     * index may hold any, so it counts as several.
     */
    private fun indexCopy(choice: Choice): Boolean? =
        when (choice) {
            is Choice.One -> choice.copy is Copy.Compiled
            is Choice.Several, Choice.Unknown -> false
            Choice.None -> null
        }

    /**
     * The project's files [from]'s run reaches by path, through [from]'s file system, with [stamps] gaining each read;
     * where it is [reading] config, it runs no file of the build.
     */
    private fun runFiles(
        from: VirtualFile?,
        inputs: Inputs,
        within: Map<VirtualFile, Map<String, Answer>>,
        memo: Memo,
        stamps: Stamps,
        reading: Boolean,
        buildRead: () -> BuildRead?,
        transient: (Transient) -> Unit,
    ): RunFiles {
        // A path means what it does on the SDK's machine, which this file system is only where `runPath` says.
        val pathFileSystem = from?.takeIf { runPath(it, inputs.os, OS.CURRENT) != null }?.fileSystem
        // The file whose index `Code`'s follows ask which copy of a module it holds.
        val asker by lazy(LazyThreadSafetyMode.NONE) { from?.let(::read) }
        // The files whose follows an ensure reached, in the run or any it followed.
        val reachedFiles = HashSet<VirtualFile>()
        // Which copy the asker's index holds of each module the run or any it followed asked about, chosen once.
        val choices = HashMap<String, Choice>()
        fun choiceOf(module: String): Choice? = asker?.let { choices.getOrPut(module) { chosen(it, module, stamps, transient) } }

        return object : RunFiles {
            override fun exists(path: String): Boolean? {
                val fileSystem = pathFileSystem ?: return null

                return stamps.find(fileSystem, path) != null
            }

            override fun imported(path: String, read: ConfigRead): FileExpansion? {
                val target = stamps.find(pathFileSystem, path)?.takeUnless { it in within } ?: return null
                val psi = read(target) ?: return null
                val child = compute(psi, inputs, within, memo, config = read)
                stamps.addAll(child.loaded.stamps)
                transient(child.transient)

                return child.expansion
            }

            override fun followed(path: String): FollowedFile? {
                val target = stamps.find(pathFileSystem, path)?.takeUnless { it.isDirectory } ?: return null
                val psi = read(target) ?: return null
                stamps.source(target)

                return FollowedFile(Lowering.lower(psi, inputs.level), target)
            }

            override fun parsed(text: String, line: Int): ElixirAst {
                val psi = ElementFactory.createFile(project, text)

                return Lowering.lower(psi, inputs.level, firstLine = line)
            }

            // A read that declined may have required any path.
            override fun requiredByRead(path: String): Require? =
                buildRead()?.table?.let { table ->
                    if (path in table.required) Require.REQUIRED else Require.MAYBE.takeIf { table.declined }
                }

            override fun copyOf(module: String): Boolean? = choiceOf(module)?.let(::indexCopy)

            override fun loadedWith(module: String): List<FollowedLoad> {
                if (reading) return emptyList()
                val copy = (choiceOf(module) as? Choice.One)?.copy as? Copy.Source ?: return emptyList()
                // A file still running has followed only part of what it will, and asking it again wouldn't end.
                if (copy.virtualFile in within) return emptyList()
                // The file compiled once, so what its follows loaded comes once, not over what the run loaded since.
                if (!reachedFiles.add(copy.virtualFile)) return emptyList()
                val run = sourceClauses(copy, inputs, within, memo)

                stamps.addAll(run.loaded.stamps)
                transient(run.transient)

                return run.loaded.followed.map { FollowedLoad(it, run.modules[it.name]) }
            }

            override fun wildcard(pattern: String): List<String>? {
                val fileSystem = pathFileSystem ?: return null
                val slash = pattern.lastIndexOf('/')
                val prefix = pattern.substring(0, slash.coerceAtLeast(0))
                if (prefix.any { it in ConfigFiles.WILDCARD }) return null
                stamps.track(names.listing(prefix))
                val glob = pattern.substring(slash + 1)
                val matcher = try {
                    FileSystems.getDefault().getPathMatcher("glob:$glob")
                } catch (_: PatternSyntaxException) {
                    return null
                }

                return fileSystem.findFileByPath(prefix)?.children.orEmpty()
                    .filter { !it.name.startsWith(".") && matcher.matches(Path.of(it.name)) }
                    .map { "$prefix/${it.name}" }
                    .sorted()
            }
        }
    }

    /**
     * The build [file] is compiled in, `null` where it isn't known: a file a config [read] evaluates is built as the read
     * is; a project file in [defaultBuildEnv]; a dependency's reads the config of the one `_build` env holding it.
     */
    private fun buildTarget(file: VirtualFile?, read: ConfigRead?, stamps: Stamps): BuildTarget? {
        read?.let { return BuildTarget(it.env, it.env, it.target) }
        file ?: return null
        // A `deps`, `_build` or `mix.exs` created or deleted moves the build.
        stamps.track(names.above(file.path))
        buildEnv(file)?.let { return BuildTarget(it, null) }
        val deps = generateSequence(file.parent) { it.parent }.firstOrNull { isDepsOfAMixProject(it) }

        if (deps != null) {
            val dependency = generateSequence(file) { it.parent }.first { it.parent == deps }.name
            val build = deps.parent.findChild("_build")?.children.orEmpty()
            // An env, or the dependency in one, created or deleted moves the build.
            stamps.track(names.listing("${deps.parent.path}/_build"))
            build.forEach { env -> stamps.track(names.path("${env.path}/lib/$dependency")) }
            val envs = build.filter { it.findFileByRelativePath("lib/$dependency") != null }

            return BuildTarget(envs.singleOrNull()?.name, null)
        }
        if (!ProjectFileIndex.getInstance(project).isInContent(file)) return BuildTarget(null, null)
        // Marking a folder as test sources moves the build.
        stamps.track(ProjectRootManager.getInstance(project))
        val env = defaultBuildEnv(project, file)

        return BuildTarget(env, env)
    }

    /**
     * Whether [file], a compiled file on the code path, is a dependency's build output rather than the project's own. A
     * file in the project's content is its own build output: Mix removes an app's stale `.beam` files before compiling
     * it, and adds its consolidated protocols only once it has compiled.
     */
    private fun isDependencyOutput(file: VirtualFile): Boolean {
        val index = ProjectFileIndex.getInstance(project)

        return index.isInLibraryClasses(file) && !index.isInContent(file)
    }

    /**
     * What the project declares of [app]. Its `.app` file as read from those of its copies on the code path that [asker]
     * loads ([Module.loads]), as a build in any of their envs sees it. [AppDeclaration.Absent] where no `.app` of that name
     * is at any class root and the project can't have the app otherwise ([mayHave]). Else [AppDeclaration.Unknown], also
     * where a copy can't be read or names another app, or two differ in env, as `=:=` tells on [level]'s VM or by a
     * zero's sign, which the VM keeps where `=:=` can't tell it.
     */
    private fun appFile(
        app: String,
        level: ElixirLanguageLevel,
        asker: PsiFile,
        stamps: Stamps,
        mayHaveApp: () -> Boolean,
    ): AppDeclaration {
        stamps.track(names.appFiles)
        stamps.track(ProjectRootManager.getInstance(project))
        val facetSdkRoots = FacetModels.getInstance(project).named()
            .flatMap { it.rootProvider.getFiles(OrderRootType.CLASSES).asList() }
        val anywhere = (OrderEnumerator.orderEntries(project).classes().roots.asList() + facetSdkRoots)
            .mapNotNull { it.findChild("$app.app") }
            .distinct()

        if (anywhere.isEmpty()) return if (mayHaveApp()) AppDeclaration.Unknown else AppDeclaration.Absent

        val onPath = anywhere.filter(::isDependencyOutput)
        val copies = (Module.loads(asker, onPath)?.let(onPath::filter) ?: onPath)
            .map { file ->
                stamps.onDisk(file)

                try {
                    AppFile.read(VfsUtilCore.loadText(file), level)
                } catch (_: IOException) {
                    null
                } ?: return AppDeclaration.Unknown
            }

        return copies.firstOrNull()
            ?.takeIf { first -> copies.all { it.name == app && sameEnv(it.env, first.env, level) } }
            ?.let(AppDeclaration::Read)
            ?: AppDeclaration.Unknown
    }

    /**
     * Whether the project may have [app] though no `.app` of it is on the code path: a `deps/[app]` folder directly under
     * a content root, a library Mix sync made for it, or a content `mix.exs` that may name it ([ConfigFiles.mayHave]).
     */
    private fun mayHave(app: String): Boolean =
        ProjectRootManager.getInstance(project).contentRoots.any { depFolder(it, app) != null } ||
            hasMixLibrary(app) ||
            ConfigFiles.mayHave(project, app)

    /** Whether a library of Mix's kind named for [app] ([scopedDepLibraryName]) is among the project's order entries. */
    private fun hasMixLibrary(app: String): Boolean {
        var found = false

        OrderEnumerator.orderEntries(project).forEachLibrary { library ->
            found = (library as? LibraryEx)?.kind == MixLibraryKind &&
                library.name?.let { name -> name == scopedLibraryNameToken(name)?.let { scopedDepLibraryName(it, app) } } == true

            !found
        }

        return found
    }

    /** The content `mix.exs` files name the apps and dependencies the answers about the project's apps rest on. */
    private fun trackMixExs(stamps: Stamps) {
        stamps.track(names.exsFiles)
        FilenameIndex.getVirtualFilesByName(MIX_EXS, GlobalSearchScope.projectScope(project)).forEach(stamps::source)
    }

    private fun sameEnv(left: Map<OtpErlangObject, OtpErlangObject>, right: Map<OtpErlangObject, OtpErlangObject>, level: ElixirLanguageLevel) =
        left.keys == right.keys &&
            left.all { (key, value) ->
                try {
                    val other = right.getValue(key)

                    equal(value, other, true, level) && !differInSign(value, other)
                } catch (_: Declined) {
                    false
                }
            }

    /**
     * The directory Elixir compiled [file] in: the innermost Mix project's holding it, as Mix compiles a dependency and
     * each umbrella app in its own.
     */
    private fun compileDirectory(file: VirtualFile): String? =
        generateSequence(file.parent) { it.parent }.firstOrNull { it.findChild(MIX_EXS) != null }?.path

    /**
     * A [Copy] as held: its file, whether it is a `.beam`, and for a `.beam`, the source its library or SDK attaches
     * ([Module.Loadable.sourceOf]).
     */
    private class HeldCopy(val virtualFile: VirtualFile, val compiled: Boolean, val source: VirtualFile? = null)

    /**
     * A name's copies as held: the [chosen], as [Resolver.preferred] orders them, and the [fallback], the `.beam`s of the
     * libraries and the SDK outside the project's content not among them, with the [stamps] they hold while.
     */
    private class HeldCopies(val chosen: List<HeldCopy>, val fallback: List<HeldCopy>, val stamps: Stamps)

    /** Where Elixir loads a module from. */
    internal sealed interface Copy {
        val virtualFile: VirtualFile

        /** @property source the source its library or SDK attaches, as [Located.Compiled.source] */
        data class Compiled(override val virtualFile: VirtualFile, val source: VirtualFile?) : Copy
        data class Source(val file: ElixirFile, override val virtualFile: VirtualFile) : Copy
    }

    private data class Answer(val exports: ModuleExports, val struct: ModuleStruct)

    private class Looked(val answer: Answer, val stamps: Stamps, val transient: Transient)

    /** A module's clauses as [clauses] read them, with what they were read from. */
    private class ReadClauses(val clauses: ModuleClauses?, val stamps: Stamps, val transient: Transient)

    /**
     * A source file's modules as its expansion compiled them.
     *
     * @property uncompiled the answer for a module the index names in the file, or whose nearest parent name the file
     *   defines, that its expansion didn't compile: unreadable where the run stopped short of one Elixir may have
     *   compiled, and `null` where the file leaves the name no module, so it answers as it would without the file
     * @property unfollowedRan whether code the expander doesn't follow ran in the file's run, which may have created a
     *   module under a name no `defmodule` in the file names ([FileExpansion.unfollowedRan])
     * @property followed the modules the file's follows loaded, which a module that ensures one of its own is compiled
     *   then sees, with their clauses in [clauseCache]
     */
    private class Loaded(
        val inputs: Inputs,
        val modules: Map<String, Answer>,
        val uncompiled: Answer?,
        val unfollowedRan: Boolean,
        val stamps: Stamps,
        val followed: List<FollowedModule>,
    ) {
        fun isCurrent(inputs: Inputs): Boolean = inputs == this.inputs && stamps.isCurrent()

        /**
         * The file's answer for [atom]: the module it compiled under the name, [uncompiled] where it didn't, unknown for
         * a name found through its [parents][Choice.One.parents] where code the expander doesn't follow ran, and `null`
         * where it leaves the name no module. Code the expander doesn't follow is taken not to create a module a
         * `defmodule` names.
         */
        fun answer(atom: String, parents: Boolean): Answer? =
            modules[atom] ?: uncompiled ?: UNREADABLE.takeIf { parents && unfollowedRan }

        /** Whether this value can stand in a run below [inProgress]: it read none of those files as loaded. */
        fun holdsWhile(inProgress: Map<VirtualFile, Map<String, Answer>>): Boolean = inProgress.keys.none(stamps::reads)
    }

    /** @property results each module [expansion] loaded, as its last definition in the file */
    private class Held(
        val loaded: Loaded,
        val expansion: FileExpansion,
        val results: Map<String, ExpansionResult>,
        val lowering: Lowering,
        val transient: Transient,
        val envs: EnvIndex?,
        val receivers: ReceiverIndex?,
    )

    /**
     * The values one outermost call computed that read files in progress above them, by file. A value holds for
     * another run below the same call where it read none of that run's files in progress as loaded, and each module it
     * read from one has the answer it read.
     */
    private class Memo {
        private val held = HashMap<VirtualFile, MutableList<SourceRun>>()

        fun of(virtualFile: VirtualFile, inProgress: Map<VirtualFile, Map<String, Answer>>): SourceRun? =
            held[virtualFile]?.firstOrNull { it.loaded.holdsWhile(inProgress) && it.transient.holdsWhile(inProgress) }

        fun add(virtualFile: VirtualFile, value: SourceRun) {
            held.getOrPut(virtualFile, ::ArrayList) += value
        }
    }

    /**
     * What an answer read that holds only for the run that read it, so a value that read any is not stored.
     *
     * @property reentered the files in progress above the run, which it read as compiling, each with the answer it
     *   read for each module, `null` where that file had loaded none under the name
     * @property dumb whether it asked for a copy while the index couldn't be read
     */
    private data class Transient(val reentered: Map<VirtualFile, Map<String, Answer?>>, val dumb: Boolean) {
        val isNone: Boolean get() = reentered.isEmpty() && !dumb

        /** Whether each module this read from a file in progress still has, below [inProgress], the answer it read. */
        fun holdsWhile(inProgress: Map<VirtualFile, Map<String, Answer>>): Boolean =
            reentered.all { (file, answers) ->
                file in inProgress && answers.all { (atom, answer) -> runningAnswer(inProgress, file, atom) == answer }
            }

        /** As the run of [virtualFile] leaves it: that file reads itself as compiling for its own run only. */
        fun leaving(virtualFile: VirtualFile?): Transient = copy(reentered = reentered - setOfNotNull(virtualFile))

        companion object {
            val NONE = Transient(emptyMap(), false)

            /** [transients] as one, a later answer for a module replacing an earlier. */
            fun merged(transients: List<Transient>): Transient {
                val all = HashMap<VirtualFile, Map<String, Answer?>>()

                for ((reentered) in transients) {
                    ProgressManager.checkCanceled()

                    reentered.forEach { (file, answers) -> all.merge(file, answers) { mine, theirs -> mine + theirs } }
                }

                return Transient(all, transients.any(Transient::dumb))
            }

            val DUMB = Transient(emptyMap(), true)
        }
    }

    /** A `.beam`'s answer, read at [stamp]. */
    private class Read(val stamp: Long, val answer: Answer)

    /** A file's clauses, as [clauseCache] holds them. */
    private sealed interface Clauses {
        fun weight(): Long

        /** A `.beam`'s, read at [stamp] and [level]; `null` where it holds none. */
        class Compiled(val stamp: Long, val level: ElixirLanguageLevel, val clauses: ModuleClauses?) : Clauses {
            override fun weight(): Long = clauses?.weight() ?: 0
        }

        /** Each module's of a source file, as the expansion that [loaded] stamps compiled them. */
        class Source(val loaded: Loaded, val modules: Map<String, ModuleClauses>) : Clauses {
            override fun weight(): Long = modules.values.sumOf(ModuleClauses::weight)
        }

        /**
         * Each module's that the follows of the config reads of one root config file loaded, by the key each read is held
         * under in [tables].
         */
        class Read(val reads: Map<TableKey, ReadModules>) : Clauses {
            override fun weight(): Long = reads.values.sumOf { read -> read.modules.values.sumOf(ModuleClauses::weight) }
        }

        /** The clauses of the modules one read's follows loaded, as the read that [stamps] stamp loaded them. */
        class ReadModules(val stamps: Stamps, val modules: Map<String, ModuleClauses>)
    }

    /** Values by file, which let go of deleted files' when a value is stored after `VFS_STRUCTURE_MODIFICATIONS` moves. */
    private class ByFile<V : Any> {
        private val values = ConcurrentHashMap<VirtualFile, V>()

        @Volatile
        private var pruned = -1L

        operator fun get(file: VirtualFile): V? = values[file]

        operator fun set(file: VirtualFile, value: V) {
            val count = VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS.modificationCount

            if (count != pruned) {
                pruned = count
                values.keys.removeIf { !it.isValid }
            }

            values[file] = value
        }
    }

    /** What an answer read, each with the count it had when read, so a later read can tell whether any moved. */
    private inner class Stamps {
        private val read = LinkedHashMap<Any, Stamp>()

        fun track(tracker: ModificationTracker) {
            read.getOrPut(tracker) { Stamp(tracker::getModificationCount) }
        }

        /**
         * The view provider's stamp, not the PsiFile's: a PsiFile created again after its tree was collected restarts
         * its own stamp at 0.
         */
        fun source(virtualFile: VirtualFile) {
            read.getOrPut(virtualFile) {
                Stamp {
                    if (virtualFile.isValid) {
                        PsiManager.getInstance(project).findViewProvider(virtualFile)?.modificationStamp ?: -1
                    } else {
                        -1
                    }
                }
            }
        }

        /** A file with no virtual file, as completion's copy of the file being edited is: its own stamp. */
        fun own(file: PsiFile) {
            read.getOrPut(file) { Stamp(file::getModificationStamp) }
        }

        fun copy(copy: Copy) {
            when (copy) {
                is Copy.Compiled -> onDisk(copy.virtualFile)
                is Copy.Source -> source(copy.virtualFile)
            }
        }

        /**
         * A file read from disk, not through its PSI, so that a keystroke keeps it: it moves when the file goes, moves or
         * changes. Keyed apart from [source], so that [reads] doesn't see it.
         */
        fun onDisk(virtualFile: VirtualFile) {
            val key = OnDisk(virtualFile)
            if (key in read) return
            track(names.path(virtualFile.path))
            read[key] = Stamp { if (virtualFile.isValid) virtualFile.modificationStamp else -1L }
        }

        /** The file at [path] in [fileSystem], held on [path], so that a file created or deleted there moves the answer. */
        fun find(fileSystem: VirtualFileSystem?, path: String): VirtualFile? {
            track(names.path(path))

            return fileSystem?.findFileByPath(path)
        }

        fun addAll(stamps: Stamps) {
            stamps.read.forEach { (key, stamp) -> read.putIfAbsent(key, stamp) }
        }

        fun isCurrent(): Boolean = read.values.all(Stamp::isCurrent)

        fun reads(virtualFile: VirtualFile): Boolean = virtualFile in read
    }

    private data class OnDisk(val virtualFile: VirtualFile)

    private class Stamp(private val count: () -> Long) {
        private val at = count()

        fun isCurrent(): Boolean = count() == at
    }

    companion object {
        fun getInstance(project: Project): ProjectModules = project.service()

        /**
         * The path Elixir compiles [file] at, where its SDK runs as [sdk] and the IDE as [host]: the file's own path where
         * it is on a local file system and the two are the same OS, else `null`. A drive letter is lowercased, as
         * Elixir's `Path.expand/1` has it on Windows.
         */
        fun runPath(file: VirtualFile?, sdk: OS, host: OS): String? =
            file?.takeIf { it.isInLocalFileSystem && sdk == host }?.path?.let { path ->
                if (sdk == OS.Windows) String(Filename.lowercaseDrive(path.toByteArray())) else path
            }

        /** Each module [expansion] compiled, a nested module after those around it. */
        fun results(expansion: FileExpansion): Sequence<ExpansionResult> =
            generateSequence(expansion.modules) { results -> results.flatMap { it.nested }.ifEmpty { null } }.flatten()

        /** The bytes the clause cache may retain, apart from the answers' own bound. */
        private const val CLAUSE_BOUND = 64L shl 20

        private val REACHED_ENVS = listOf("dev", "test", "prod")

        private val ABSENT = Answer(ModuleExports.Absent, ModuleStruct.Absent)
        private val UNREADABLE = Answer(ModuleExports.Unreadable, ModuleStruct.Unreadable)

        /**
         * [atom]'s answer from [file], whose expansion is in progress: loaded once its `defmodule` has finished, and
         * `null` while the file has loaded no module under the name.
         */
        private fun runningAnswer(
            inProgress: Map<VirtualFile, Map<String, Answer>>,
            file: VirtualFile,
            atom: String,
        ): Answer? = inProgress.getValue(file)[atom]
    }
}
