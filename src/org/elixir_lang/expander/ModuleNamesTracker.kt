package org.elixir_lang.expander

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.PsiTreeChangeAdapter
import com.intellij.psi.PsiTreeChangeEvent
import com.intellij.psi.stubs.StubTreeLoader
import com.intellij.util.concurrency.ThreadingAssertions
import org.elixir_lang.ElixirFileType
import org.elixir_lang.Module
import org.elixir_lang.mix.Project.MIX_EXS
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.call.StubBased
import org.elixir_lang.psi.definition
import org.elixir_lang.psi.stub.call.Stubbic
import org.elixir_lang.psi.stub.type.Named
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.elixir_lang.beam.FileType as BeamFileType

/**
 * Moves a module's name, as the index names it, when an Elixir or `.beam` file may have come to define it or stopped:
 * [name] is one name's count, and this tracker's own count moves with every name's.
 *
 * Read a count under a read action. A file changed through its PSI is named from its PSI at the next read. A file
 * written through the VFS other than by a save, created or copied into the project's content or libraries, or moved or
 * renamed, waits for the next read and is named from its stub, as is each file under a directory arriving so; in dumb
 * mode every name moves instead. A file's deletion moves no name.
 */
@Service(Service.Level.PROJECT)
internal class ModuleNamesTracker @JvmOverloads constructor(
    private val project: Project,
    /** The names a file defines, as its stub has them. */
    private val namesOf: (VirtualFile) -> Set<String> = { stubNames(project, it) },
    /** Hands each file under an arrived directory to its second argument. */
    private val walk: (VirtualFile, (VirtualFile) -> Unit) -> Unit = ::walkFiles,
) : SimpleModificationTracker(), Disposable {

    /** Each file's module names when its PSI was last named. */
    private val names = ConcurrentHashMap<VirtualFile, Set<String>>()

    @get:TestOnly
    internal val named: Set<VirtualFile> get() = names.keys.toSet()

    /** The files changed through their PSI since they were last named. */
    private val changed: MutableSet<VirtualFile> = ConcurrentHashMap.newKeySet()

    /** The files to name from their stubs. */
    private val pending: MutableSet<VirtualFile> = ConcurrentHashMap.newKeySet()

    /** The directories arrived, whose files are added to [pending]. */
    private val arrivedDirectories: MutableSet<VirtualFile> = ConcurrentHashMap.newKeySet()

    /** The files and directories a reader is naming or walking, which another reader goes on without. */
    private val claimed: MutableSet<VirtualFile> = ConcurrentHashMap.newKeySet()

    private val byName = ConcurrentHashMap<String, NameCount>()

    /** Moved when every name moves. */
    private val everyName = AtomicLong()

    private inner class NameCount : ModificationTracker {
        val own = AtomicLong()

        override fun getModificationCount(): Long {
            // Names what waits.
            this@ModuleNamesTracker.modificationCount

            return own.get() + everyName.get()
        }
    }

    init {
        PsiManager.getInstance(project).addPsiTreeChangeListener(
            object : PsiTreeChangeAdapter() {
                override fun beforeChildAddition(event: PsiTreeChangeEvent) = before(event)
                override fun beforeChildRemoval(event: PsiTreeChangeEvent) = before(event)
                override fun beforeChildReplacement(event: PsiTreeChangeEvent) = before(event)
                override fun beforeChildMovement(event: PsiTreeChangeEvent) = before(event)
                override fun beforeChildrenChange(event: PsiTreeChangeEvent) = before(event)
                override fun childAdded(event: PsiTreeChangeEvent) = after(event)
                override fun childRemoved(event: PsiTreeChangeEvent) = after(event)
                override fun childReplaced(event: PsiTreeChangeEvent) = after(event)
                override fun childMoved(event: PsiTreeChangeEvent) = after(event)
                override fun childrenChanged(event: PsiTreeChangeEvent) = after(event)
            },
            this,
        )
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    events.forEach(::arrived)

                    // A deleted directory's events name only the directory.
                    if (events.any { it is VFileDeleteEvent }) names.keys.removeIf { !it.isValid }
                }
            },
        )
    }

    /** What moves when a module named [atom] may have come or gone. */
    fun name(atom: String): ModificationTracker = byName.computeIfAbsent(Module.indexName(atom)) { NameCount() }

    /**
     * What moves when [file], an asker, moves: its path's hash, unsigned, as a `CachedValue` takes a negative count to be
     * out of date. Out of date when the file is gone; never moves for a light asker, which has no file.
     */
    fun askerPath(file: VirtualFile?): ModificationTracker =
        ModificationTracker {
            when {
                file == null -> 0L
                file.isValid -> file.path.hashCode().toUInt().toLong()
                else -> -1L
            }
        }

    override fun getModificationCount(): Long {
        drain()

        if (changed.isNotEmpty()) {
            ThreadingAssertions.assertReadAccess()

            for (virtualFile in changed.toList()) {
                val now = virtualFile
                    .takeIf { it.isValid }
                    ?.let { PsiManager.getInstance(project).findFile(it) as? ElixirFile }
                    ?.let(::psiNames)

                changed.remove(virtualFile)

                val was = (if (now == null) names.remove(virtualFile) else names.put(virtualFile, now)).orEmpty()
                val current = now.orEmpty()
                moved((current - was) + (was - current))
            }
        }

        return super.getModificationCount()
    }

    override fun dispose() {}

    private fun arrived(event: VFileEvent) {
        when (event) {
            is VFileContentChangeEvent -> if (!event.isFromSave && definesModules(event.file) && inProject(event.file)) pending += event.file
            is VFileCreateEvent -> event.file?.takeIf(::inProject)?.let(::arrived)
            is VFileCopyEvent -> event.findCreatedFile()?.takeIf(::inProject)?.let(::arrived)
            is VFileMoveEvent -> if (movedHere(event.file, event.oldParent)) arrived(event.file)
            is VFilePropertyChangeEvent -> if (event.isRename && movedHere(event.file, event.file.parent)) arrived(event.file)
            else -> Unit
        }
    }

    /** A file, or a directory whose old parent or new place is in the project: one moved elsewhere names nothing here. */
    private fun movedHere(file: VirtualFile, oldParent: VirtualFile?): Boolean =
        !file.isDirectory || inProject(file) || oldParent?.let(::inProject) == true

    private fun arrived(file: VirtualFile) {
        if (file.isDirectory) arrivedDirectories += file else if (definesModules(file)) pending += file
    }

    private fun definesModules(file: VirtualFile): Boolean = file.fileType.let { it is ElixirFileType || it is BeamFileType }

    private fun inProject(file: VirtualFile): Boolean = ProjectFileIndex.getInstance(project).isInProject(file)

    /** Names the files waiting, each on one reader: a reader goes on without what another has claimed. */
    private fun drain() {
        if (arrivedDirectories.isEmpty() && pending.isEmpty()) return
        ThreadingAssertions.assertReadAccess()

        claiming(arrivedDirectories) { directory ->
            if (directory.isValid) walk(directory) { file -> if (!file.isDirectory && definesModules(file)) pending += file }
        }

        if (DumbService.isDumb(project)) {
            // Before any file is taken, so a cancel can't take one with its move unmade.
            everyName.incrementAndGet()
            incModificationCount()
            claiming(pending) { file -> names.remove(file) }
        } else {
            claiming(pending) { file ->
                moved(file.takeIf { it.isValid }?.let(namesOf).orEmpty() + names.remove(file).orEmpty())
            }
        }
    }

    /** Runs [action] on each of [waiting] no other reader has claimed, then removes it: a cancel leaves it waiting. */
    private fun claiming(waiting: MutableSet<VirtualFile>, action: (VirtualFile) -> Unit) {
        for (file in waiting) {
            if (!claimed.add(file)) continue

            try {
                ProgressManager.checkCanceled()
                action(file)
                waiting.remove(file)
            } finally {
                claimed.remove(file)
            }
        }
    }

    private fun moved(moduleNames: Collection<String>) {
        if (moduleNames.isEmpty()) return

        for (name in moduleNames) byName[name]?.own?.incrementAndGet()
        incModificationCount()
    }

    /**
     * A file's names before its first change, so that a change that keeps them moves none. Only from a tree already
     * loaded: one built here from new text would not match the stub the index still holds.
     */
    private fun before(event: PsiTreeChangeEvent) {
        val file = event.file as? ElixirFile ?: return
        if (!file.isContentsLoaded) return
        val virtualFile = file.virtualFile ?: return

        names.computeIfAbsent(virtualFile) { psiNames(file) }
    }

    private fun after(event: PsiTreeChangeEvent) {
        val file = event.file as? ElixirFile ?: return

        file.virtualFile?.let(changed::add)
    }

    private fun psiNames(file: ElixirFile): Set<String> {
        val names = HashSet<String>()

        file.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element is StubBased<*>) {
                    ProgressManager.checkCanceled()
                    names += Named.modularNames(definition(element)) { element.canonicalNameSet() }
                }

                super.visitElement(element)
            }
        })

        return names
    }

    companion object {
        fun getInstance(project: Project): ModuleNamesTracker = project.service()

        private fun stubNames(project: Project, file: VirtualFile): Set<String> {
            val tree = StubTreeLoader.getInstance().readOrBuild(project, file, null) ?: return emptySet()
            val names = HashSet<String>()

            for (stub in tree.plainList) {
                ProgressManager.checkCanceled()
                val stubbic = stub as? Stubbic ?: continue
                names += Named.modularNames(stubbic.definition) { stubbic.canonicalNameSet() }
            }

            return names
        }

        internal fun walkFiles(directory: VirtualFile, found: (VirtualFile) -> Unit) {
            VfsUtilCore.iterateChildrenRecursively(directory, null) { file ->
                ProgressManager.checkCanceled()
                found(file)
                true
            }
        }
    }
}
