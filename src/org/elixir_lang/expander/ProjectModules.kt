package org.elixir_lang.expander

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.Lowering
import org.elixir_lang.navigation.isDecompiled
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.reference.resolver.Module
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * What each module exports and its struct, read from the copy Elixir loads for the file that asks: a `.beam`, or a
 * source file, which is read by expanding it. Answers are held, keyed by [VirtualFile], while every file and tracker
 * they read is unchanged.
 */
@Service(Service.Level.PROJECT)
internal class ProjectModules(private val project: Project) {
    /** What an expansion reads besides the project's files. An answer held for other inputs is recomputed. */
    data class Inputs(val level: ElixirLanguageLevel, val kernel: KernelImports)

    private val files = ByFile<Loaded>()
    private val beams = ByFile<Read>()

    /** Per holder, so a new holder computes afresh. */
    private val held = Key.create<Held>("org.elixir_lang.expander.ProjectModules.held")

    /** [atom]'s exports, from the copy Elixir loads for [asker]. */
    @RequiresReadLock
    fun exports(asker: PsiFile, atom: String, inputs: Inputs): ModuleExports = lookup(asker, atom, inputs).answer.exports

    /** [atom]'s struct, from the copy Elixir loads for [asker]. */
    @RequiresReadLock
    fun struct(asker: PsiFile, atom: String, inputs: Inputs): ModuleStruct = lookup(asker, atom, inputs).answer.struct

    /** [file]'s expansion, against the modules the other files define, held on [file] while what it read is unchanged. */
    @RequiresReadLock
    fun expansion(file: ElixirFile, inputs: Inputs): FileExpansion {
        val virtualFile = file.virtualFile ?: return compute(file, inputs).expansion
        file.getUserData(held)?.takeIf { it.loaded.isCurrent(inputs) }?.let { return it.expansion }

        val computed = compute(file, inputs)
        files[virtualFile] = computed.loaded
        file.putUserData(held, computed)

        return computed.expansion
    }

    private fun lookup(asker: PsiFile, atom: String, inputs: Inputs): Looked {
        val copies = Module
            .resolvePreferred(asker, org.elixir_lang.Module.indexName(atom), false, inScope = false, loaded = true)
            .mapNotNull { copy(it.element) }
            .distinctBy { it.virtualFile }
        val stamps = Stamps()
        stamps.track(VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS)
        stamps.track(ModuleNamesTracker.getInstance(project))
        stamps.track(ProjectRootManager.getInstance(project))

        val answer = when (val copy = copies.singleOrNull()) {
            null -> {
                copies.forEach { stamps.copy(it) }

                if (copies.isEmpty()) ABSENT else UNREADABLE
            }
            is Copy.Compiled -> {
                stamps.copy(copy)

                compiled(copy.virtualFile)
            }
            is Copy.Source -> {
                val loaded = loaded(copy, inputs)
                stamps.addAll(loaded.stamps)

                loaded.modules[atom] ?: loaded.uncompiled
            }
        }

        return Looked(answer, stamps)
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

    private fun copy(element: PsiElement): Copy? {
        val file = element.containingFile?.originalFile ?: return null
        val virtualFile = file.virtualFile ?: return null

        return when {
            element.isDecompiled() -> Copy.Compiled(virtualFile)
            file is ElixirFile -> Copy.Source(file, virtualFile)
            else -> null
        }
    }

    private fun loaded(copy: Copy.Source, inputs: Inputs): Loaded =
        files[copy.virtualFile]?.takeIf { it.isCurrent(inputs) }
            ?: compute(copy.file, inputs).loaded.also { files[copy.virtualFile] = it }

    /** Only a computation that completes is stored, so a cancelled one leaves nothing behind. */
    private fun compute(file: ElixirFile, inputs: Inputs): Held {
        val stamps = Stamps()
        file.virtualFile?.let { stamps.source(it) }
        val looked = HashMap<String, Looked>()
        val ask = { atom: String -> looked.getOrPut(atom) { lookup(file, atom, inputs) }.answer }
        val expansion = Expander.expandFile(
            Lowering.lower(file, inputs.level),
            Env.empty(inputs.level, inputs.kernel),
            inputs.level,
            { ask(it).exports },
            { ask(it).struct },
        )
        looked.values.forEach { stamps.addAll(it.stamps) }

        val modules = generateSequence(expansion.modules) { results -> results.flatMap { it.nested }.ifEmpty { null } }
            .flatten()
            .associate { it.module to Answer(LoadedModules.exports(it), LoadedModules.struct(it)) }
        val uncompiled = Answer(LoadedModules.uncompiledExports(expansion), LoadedModules.uncompiledStruct(expansion))

        return Held(Loaded(inputs, modules, uncompiled, stamps), expansion)
    }

    /** Where Elixir loads a module from. */
    private sealed interface Copy {
        val virtualFile: VirtualFile

        data class Compiled(override val virtualFile: VirtualFile) : Copy
        data class Source(val file: ElixirFile, override val virtualFile: VirtualFile) : Copy
    }

    private data class Answer(val exports: ModuleExports, val struct: ModuleStruct)

    private class Looked(val answer: Answer, val stamps: Stamps)

    /**
     * A source file's modules as its expansion compiled them.
     *
     * @property uncompiled the answer for a module the index names in the file that its expansion didn't compile
     */
    private class Loaded(
        val inputs: Inputs,
        val modules: Map<String, Answer>,
        val uncompiled: Answer,
        val stamps: Stamps,
    ) {
        fun isCurrent(inputs: Inputs): Boolean = inputs == this.inputs && stamps.isCurrent()
    }

    private class Held(val loaded: Loaded, val expansion: FileExpansion)

    /** A `.beam`'s answer, read at [stamp]. */
    private class Read(val stamp: Long, val answer: Answer)

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

        fun copy(copy: Copy) {
            when (copy) {
                is Copy.Compiled -> read.getOrPut(copy.virtualFile) { Stamp(copy.virtualFile::getModificationStamp) }
                is Copy.Source -> source(copy.virtualFile)
            }
        }

        fun addAll(stamps: Stamps) {
            stamps.read.forEach { (key, stamp) -> read.putIfAbsent(key, stamp) }
        }

        fun isCurrent(): Boolean = read.values.all(Stamp::isCurrent)
    }

    private class Stamp(private val count: () -> Long) {
        private val at = count()

        fun isCurrent(): Boolean = count() == at
    }

    companion object {
        fun getInstance(project: Project): ProjectModules = project.service()

        private val ABSENT = Answer(ModuleExports.Absent, ModuleStruct.Absent)
        private val UNREADABLE = Answer(ModuleExports.Unreadable, ModuleStruct.Unreadable)
    }
}
