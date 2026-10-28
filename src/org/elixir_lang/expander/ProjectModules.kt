package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangObject
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
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
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Lowering
import org.elixir_lang.mix.Project.MIX_EXS
import org.elixir_lang.model.psi.config.ConfigFiles
import org.elixir_lang.model.psi.config.ConfigSettings
import org.elixir_lang.navigation.isDecompiled
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.Import.Term
import org.elixir_lang.reference.Resolver
import org.elixir_lang.reference.resolver.Module
import org.jetbrains.annotations.TestOnly
import java.io.IOException
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

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
        clauses(asker, atom, inputs, compiling(asker)).clauses

    /**
     * [atom]'s clauses for [asker], with the stamps of what was read for them, as [lookup] gives an answer's.
     */
    private fun clauses(
        asker: PsiFile,
        atom: String,
        inputs: Inputs,
        inProgress: Map<VirtualFile, Map<String, Answer>>,
    ): ReadClauses {
        val (choice, stamps) = chosen(asker, atom)
        var transient = Transient.NONE

        fun read(choice: Choice): ModuleClauses? =
            when (choice) {
                Choice.Unknown -> {
                    transient = Transient.DUMB
                    null
                }
                Choice.None, is Choice.Several -> null
                is Choice.One ->
                    when (val copy = choice.copy) {
                        is Copy.Compiled -> compiledClauses(copy.virtualFile, inputs.level)
                        is Copy.Source if copy.virtualFile in inProgress -> {
                            val running = runningAnswer(inProgress, copy.virtualFile, atom)
                            transient = Transient(mapOf(copy.virtualFile to mapOf(atom to running)), false)

                            if (running == null) read(choice.fallingThrough()) else null
                        }
                        is Copy.Source -> {
                            val (loaded, modules, below) = sourceClauses(copy, inputs, inProgress)
                            transient = below
                            stamps.addAll(loaded.stamps)

                            modules[atom] ?: if (loaded.answer(atom) == null) read(choice.fallingThrough()) else null
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
        class Several(val copies: List<VirtualFile>) : Located

        class Compiled(val beam: VirtualFile, val exports: ModuleExports) : Located

        /** @property result the module as [file]'s expansion compiled it, `null` where it didn't */
        class Source(val file: ElixirFile, val result: ExpansionResult?) : Located
    }

    /** The copy of [atom] Elixir loads for [asker], as [exports] and [struct] choose it. */
    @RequiresReadLock
    fun locate(asker: PsiFile, atom: String, inputs: Inputs): Located {
        fun locate(choice: Choice): Located =
            when (choice) {
                Choice.Unknown -> Located.Unknown
                Choice.None -> Located.None
                is Choice.Several -> Located.Several(choice.copies.map { it.virtualFile })
                is Choice.One ->
                    when (val copy = choice.copy) {
                        is Copy.Compiled -> Located.Compiled(copy.virtualFile, compiled(copy.virtualFile).exports)
                        is Copy.Source -> {
                            val file = if (copy.virtualFile == fileOf(asker)) asker as? ElixirFile ?: copy.file else copy.file
                            val held = held(file, inputs)

                            if (held.loaded.answer(atom) == null) locate(choice.fallingThrough()) else Located.Source(file, held.results[atom])
                        }
                    }
            }

        return locate(choose(asker, atom))
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
        file.getUserData(held)?.takeIf { it.loaded.isCurrent(inputs) }?.let { return it }

        val computed = compute(file, inputs, emptyMap(), Memo(), records = true)
        if (computed.transient.isNone) {
            file.virtualFile?.let { files[it] = computed.loaded }
            file.putUserData(held, computed)
        }

        return computed
    }

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
    ): Looked {
        val (choice, stamps) = chosen(asker, atom)
        var transient = Transient.NONE

        fun answer(choice: Choice): Answer =
            when (choice) {
                Choice.Unknown -> {
                    transient = Transient.DUMB
                    UNREADABLE
                }
                Choice.None -> ABSENT
                is Choice.Several -> UNREADABLE
                is Choice.One ->
                    when (val copy = choice.copy) {
                        is Copy.Compiled -> compiled(copy.virtualFile)
                        // Elixir loads each module as its own `defmodule` compiles, so one still compiling isn't loaded yet.
                        is Copy.Source if copy.virtualFile in inProgress -> {
                            val running = runningAnswer(inProgress, copy.virtualFile, atom)
                            transient = Transient(mapOf(copy.virtualFile to mapOf(atom to running)), false)

                            running ?: answer(choice.fallingThrough())
                        }
                        is Copy.Source -> {
                            val (loaded, below) = loaded(copy, inputs, inProgress, memo)
                            transient = below
                            stamps.addAll(loaded.stamps)

                            loaded.answer(atom) ?: answer(choice.fallingThrough())
                        }
                    }
            }

        return Looked(answer(choice), stamps, transient)
    }

    /**
     * [choose]'s choice, with the stamps that move when it may: none where it is unknown, and a source copy's own
     * stamps are its expansion's.
     */
    private fun chosen(asker: PsiFile, atom: String): Pair<Choice, Stamps> {
        val choice = choose(asker, atom)
        val stamps = Stamps()
        if (choice == Choice.Unknown) return choice to stamps
        stamps.track(VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS)
        stamps.track(ModuleNamesTracker.getInstance(project))
        stamps.track(ProjectRootManager.getInstance(project))

        when (choice) {
            is Choice.Several -> choice.copies.forEach { stamps.copy(it) }
            is Choice.One -> {
                (choice.copy as? Copy.Compiled)?.let { stamps.copy(it) }
                choice.fallback.forEach { stamps.copy(it) }
            }
            Choice.None, Choice.Unknown -> Unit
        }

        return choice to stamps
    }

    /** Which of [atom]'s copies Elixir loads for [asker]: unknown in dumb mode, else none, several or the one. */
    private fun choose(asker: PsiFile, atom: String): Choice {
        if (DumbService.isDumb(project)) return Choice.Unknown
        val (chosen, fallback) = copies(asker, atom)

        return when (val copy = chosen.singleOrNull()) {
            null -> if (chosen.isEmpty()) Choice.None else Choice.Several(chosen)
            else -> Choice.One(copy, fallback)
        }
    }

    private sealed interface Choice {
        data object Unknown : Choice
        data object None : Choice
        class Several(val copies: List<Copy>) : Choice

        /**
         * @property fallback the `.beam`s of the libraries and the SDK outside the project's content, which Elixir loads
         *   from the code path where [copy], a source file, defines no module under the name after all
         */
        class One(val copy: Copy, val fallback: List<Copy.Compiled>) : Choice {
            /** Where Elixir loads the name from once [copy], a source file, leaves it no module: the code path's `.beam`s, as [choose] reads copies. */
            fun fallingThrough(): Choice =
                when (val beam = fallback.singleOrNull()) {
                    null -> if (fallback.isEmpty()) None else Several(fallback)
                    else -> One(beam, emptyList())
                }
        }
    }

    /**
     * The copies of [atom] Elixir may load for [asker], held on [asker] by file while the trackers [lookup] stamps are
     * unchanged: the preferred ones, then the `.beam`s of the libraries and the SDK outside the project's content not
     * among them. A reparse replaces a file's PSI and moves none of them, so each source copy's PSI is found again.
     */
    private fun copies(asker: PsiFile, atom: String): Pair<List<Copy>, List<Copy.Compiled>> {
        val held = heldCopies(asker, atom)
        val chosen = held.chosen.mapNotNull { copy ->
            if (copy.compiled) {
                Copy.Compiled(copy.virtualFile)
            } else {
                (PsiManager.getInstance(project).findFile(copy.virtualFile) as? ElixirFile)?.let { Copy.Source(it, copy.virtualFile) }
            }
        }

        return chosen to held.fallback.map { Copy.Compiled(it.virtualFile) }
    }

    private fun heldCopies(asker: PsiFile, atom: String): HeldCopies {
        val byAtom = CachedValuesManager.getManager(project).getCachedValue(
            asker,
            chosen,
            {
                CachedValueProvider.Result.create(
                    ConcurrentHashMap<String, HeldCopies>(),
                    VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
                    ModuleNamesTracker.getInstance(project),
                    ProjectRootManager.getInstance(project),
                )
            },
            false,
        )
        byAtom[atom]?.let { return it }

        val loadable = Module.resolveLoadable(asker, org.elixir_lang.Module.indexName(atom))
        val chosen = Resolver.preferred(asker, false, loadable).mapNotNull { copy(it.element) }.distinctBy { it.virtualFile }
        val chosenFiles = chosen.mapTo(HashSet()) { it.virtualFile }
        val fallback = loadable.mapNotNull { copy(it.element) }
            .filter { it.compiled && it.virtualFile !in chosenFiles && isDependencyOutput(it.virtualFile) }
            .distinctBy { it.virtualFile }
        val copies = HeldCopies(chosen, fallback)

        return byAtom.putIfAbsent(atom, copies) ?: copies
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

    /** The clauses of each module [copy] loads, by expanding it again, with the expansion that compiled them. */
    private fun sourceClauses(
        copy: Copy.Source,
        inputs: Inputs,
        inProgress: Map<VirtualFile, Map<String, Answer>>,
    ): Triple<Loaded, Map<String, ModuleClauses>, Transient> {
        (clauseCache[copy.virtualFile] as? Clauses.Source)
            ?.takeIf { it.loaded.isCurrent(inputs) && it.loaded.holdsWhile(inProgress) }
            ?.let { return Triple(it.loaded, it.modules, Transient.NONE) }

        val modules = HashMap<String, ModuleClauses>()
        val computed = compute(copy.file, inputs, inProgress, Memo(), observer = clausesInto(modules))
        if (computed.transient.isNone) {
            files[copy.virtualFile] = computed.loaded
            clauseCache[copy.virtualFile] = Clauses.Source(computed.loaded, modules)
        }

        return Triple(computed.loaded, modules, computed.transient)
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
        memo.of(copy.virtualFile, inProgress)?.let { return it }

        // The clauses the expansion loads are kept, so a later read of them doesn't expand the file again.
        val modules = HashMap<String, ModuleClauses>()
        val computed = compute(copy.file, inputs, inProgress, memo, observer = clausesInto(modules))
        if (computed.transient.isNone) {
            files[copy.virtualFile] = computed.loaded
            clauseCache[copy.virtualFile] = Clauses.Source(computed.loaded, modules)
        } else if (!computed.transient.dumb) {
            memo.add(copy.virtualFile, computed.loaded to computed.transient)
        }

        return computed.loaded to computed.transient
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
    ): Held {
        val virtualFile = file.virtualFile
        val stamps = Stamps()
        if (virtualFile != null) stamps.source(virtualFile) else stamps.own(file)
        val modules = HashMap<String, Answer>()
        val results = HashMap<String, ExpansionResult>()
        val compiling = fileOf(file)
        val within = compiling?.let { inProgress + (it to modules) } ?: inProgress
        val looked = HashMap<String, Looked>()
        // A re-read answer joins the one it supersedes, as the expansion used both.
        val used = ArrayList<Looked>()
        val transients = ArrayList<Transient>()
        val ask = { atom: String ->
            looked[atom]
                ?.takeIf { it.transient.holdsWhile(within) }
                ?.answer
                ?: lookup(file, atom, inputs, within, memo).also {
                    looked[atom] = it
                    used += it
                }.answer
        }
        val top = Env.empty(inputs.level, inputs.kernel)
        val envs = if (records) EnvIndex(top) else null
        val receivers = if (records) ReceiverIndex() else null
        val lowering = Lowering.of(file, inputs.level)
        val expansion = Expander.expandFile(
            lowering.lower(file),
            top,
            inputs.level,
            { ask(it).exports },
            { ask(it).struct },
            appEnv(inputs.level, stamps, virtualFile, inProgress) { transients += it },
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
            },
            file = {
                // A rename or move raises the count, which discards this run's answers.
                stamps.track(VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS)
                runPath(virtualFile, inputs.os, OS.CURRENT)
            },
            virtualFile = virtualFile,
            os = inputs.os,
            directories = { path ->
                // A `mix.exs` created or deleted above the file moves its answer.
                stamps.track(VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS)
                virtualFile?.fileSystem?.findFileByPath(path)?.let(::compileDirectory)
            },
            clauses = { atom ->
                clauses(file, atom, inputs, within).also { read ->
                    stamps.addAll(read.stamps)
                    transients += read.transient
                }.clauses
            },
        )
        used.forEach { stamps.addAll(it.stamps) }

        val uncompiled = LoadedModules.uncompiledExports(expansion)?.let { Answer(it, ModuleStruct.Unreadable) }
        val transient = Transient.merged(transients + used.map(Looked::transient)).leaving(compiling)

        return Held(Loaded(inputs, modules, uncompiled, stamps), expansion, results, lowering, transient, envs, receivers)
    }

    /** The app env a default build of the project sees, for an expansion that keeps no stamps. */
    @RequiresReadLock
    fun appEnv(inputs: Inputs): AppEnv = appEnv(inputs.level, Stamps(), null, emptyMap()) {}

    /**
     * The app env a default build of the project sees, as [AppEnv.of] orders it: a key a config file that counts sets,
     * or may set where one of its settings can't be read, else what the app's `.app` file declares, read from a class
     * root of the libraries and the SDK outside the project's content, as the `.beam` fallback is. The project's own
     * apps are built from the sources being expanded, so their `.app` files aren't read. A read made expanding [file],
     * a config file that counts, is configured: Mix loads the config before the dependencies' `.app` files. So is one
     * made while a config file's expansion runs above [file]'s ([inProgress]), which holds only while it runs. [stamps]
     * gain what each answer read, and [transient] what holds only for this run: in dumb mode nothing can be read, and
     * each answer is unreadable.
     */
    private fun appEnv(
        level: ElixirLanguageLevel,
        stamps: Stamps,
        file: VirtualFile?,
        inProgress: Map<VirtualFile, Map<String, Answer>>,
        transient: (Transient) -> Unit,
    ): AppEnv {
        val configFiles = ArrayList<VirtualFile>()
        val config by lazy {
            // A `mix.exs` names its config directory, where a file created or deleted moves the settings.
            stamps.track(VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS)
            FilenameIndex.getVirtualFilesByName(MIX_EXS, GlobalSearchScope.projectScope(project)).forEach(stamps::source)

            ConfigFiles.scope(project).also { scope ->
                configFiles += FilenameIndex.getAllFilesByExt(project, "exs", scope)
                configFiles.forEach(stamps::source)
            }
        }
        val appFiles = HashMap<String, Optional<AppFile>>()

        // Whether this file is expanded within a config file's expansion.
        fun configRunning(): Boolean {
            val running = inProgress.keys.filter(config::contains)
            if (running.isNotEmpty()) transient(Transient(running.associateWith { emptyMap() }, false))

            return running.isNotEmpty()
        }

        val env = AppEnv.of(
            { app, key ->
                file != null && config.contains(file) || configRunning() ||
                    (key as? OtpErlangAtom)?.let { ConfigSettings.of(project, app, it.atomValue(), config).isNotEmpty() } == true
            },
            { app -> ConfigSettings.unread(project, app, config) },
            { app -> appFiles.getOrPut(app) { Optional.ofNullable(appFile(app, level, stamps)) }.orElse(null) },
        )

        return AppEnv { app, key ->
            if (DumbService.isDumb(project)) {
                transient(Transient.DUMB)
                AppEnvValue.Unreadable
            } else {
                env.of(app, key).also {
                    // Whether a config file's call sets or writes a key rests on the module its expansion names, so on
                    // what that expansion read.
                    configFiles.forEach { file -> files[file]?.stamps?.takeIf { it.isCurrent() }?.let(stamps::addAll) }
                }
            }
        }
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
     * [app]'s `.app` file as read from its copies on the code path, as a build in any of their envs sees it: `null`
     * where there is none, one can't be read or names another app, or two differ in env, as `=:=` tells on [level]'s VM
     * or by a zero's sign, which the VM keeps where `=:=` can't tell it.
     */
    private fun appFile(app: String, level: ElixirLanguageLevel, stamps: Stamps): AppFile? {
        stamps.track(VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS)
        stamps.track(ProjectRootManager.getInstance(project))
        val copies = OrderEnumerator.orderEntries(project).classes().roots
            .mapNotNull { it.findChild("$app.app") }
            .filter(::isDependencyOutput)
            .distinct()
            .map { file ->
                stamps.file(file)

                try {
                    AppFile.read(VfsUtilCore.loadText(file), level)
                } catch (_: IOException) {
                    null
                } ?: return null
            }

        return copies.firstOrNull()?.takeIf { first -> copies.all { it.name == app && sameEnv(it.env, first.env, level) } }
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

    /** A [Copy] as held: its file, and whether it is a `.beam`. */
    private class HeldCopy(val virtualFile: VirtualFile, val compiled: Boolean)

    /**
     * A name's copies as held: the [chosen], as [Resolver.preferred] orders them, and the [fallback], the `.beam`s of the
     * libraries and the SDK outside the project's content not among them.
     */
    private class HeldCopies(val chosen: List<HeldCopy>, val fallback: List<HeldCopy>)

    /** Where Elixir loads a module from. */
    private sealed interface Copy {
        val virtualFile: VirtualFile

        data class Compiled(override val virtualFile: VirtualFile) : Copy
        data class Source(val file: ElixirFile, override val virtualFile: VirtualFile) : Copy
    }

    private data class Answer(val exports: ModuleExports, val struct: ModuleStruct)

    private class Looked(val answer: Answer, val stamps: Stamps, val transient: Transient)

    /** A module's clauses as [clauses] read them, with what they were read from. */
    private class ReadClauses(val clauses: ModuleClauses?, val stamps: Stamps, val transient: Transient)

    /**
     * A source file's modules as its expansion compiled them.
     *
     * @property uncompiled the answer for a module the index names in the file that its expansion didn't compile:
     *   unreadable where the run stopped short of one Elixir may have compiled, and `null` where the file leaves the
     *   name no module, so it answers as it would without the file
     */
    private class Loaded(
        val inputs: Inputs,
        val modules: Map<String, Answer>,
        val uncompiled: Answer?,
        val stamps: Stamps,
    ) {
        fun isCurrent(inputs: Inputs): Boolean = inputs == this.inputs && stamps.isCurrent()

        /**
         * The file's answer for [atom]: the module it compiled under the name, [uncompiled] where it didn't, and `null`
         * where it leaves the name no module.
         */
        fun answer(atom: String): Answer? = modules[atom] ?: uncompiled

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
        private val held = HashMap<VirtualFile, MutableList<Pair<Loaded, Transient>>>()

        fun of(virtualFile: VirtualFile, inProgress: Map<VirtualFile, Map<String, Answer>>): Pair<Loaded, Transient>? =
            held[virtualFile]?.firstOrNull { (loaded, transient) ->
                loaded.holdsWhile(inProgress) && transient.holdsWhile(inProgress)
            }

        fun add(virtualFile: VirtualFile, value: Pair<Loaded, Transient>) {
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
                is Copy.Compiled -> file(copy.virtualFile)
                is Copy.Source -> source(copy.virtualFile)
            }
        }

        /** A file read from disk, not through its PSI. */
        fun file(virtualFile: VirtualFile) {
            read.getOrPut(virtualFile) { Stamp(virtualFile::getModificationStamp) }
        }

        fun addAll(stamps: Stamps) {
            stamps.read.forEach { (key, stamp) -> read.putIfAbsent(key, stamp) }
        }

        fun isCurrent(): Boolean = read.values.all(Stamp::isCurrent)

        fun reads(virtualFile: VirtualFile): Boolean = virtualFile in read
    }

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
