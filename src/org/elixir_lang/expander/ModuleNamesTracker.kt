package org.elixir_lang.expander

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.PsiTreeChangeAdapter
import com.intellij.psi.PsiTreeChangeEvent
import com.intellij.util.concurrency.ThreadingAssertions
import org.elixir_lang.ElixirFileType
import org.elixir_lang.psi.Definition
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.call.StubBased
import org.elixir_lang.psi.definition
import org.elixir_lang.psi.stub.type.Named
import java.util.concurrent.ConcurrentHashMap

/**
 * Moves when the modules an Elixir file defines change, named as the index names them. A file's creation, deletion or
 * move is `VFS_STRUCTURE_MODIFICATIONS`', not this tracker's.
 *
 * Read the count under a read action: the files changed through their PSI since the last read are named then, not on
 * each change. A file whose content changes on disk, other than by a save, moves the count without being named: its
 * PSI may hold no tree, and the platform reports such a change with no PSI event that names the file.
 */
@Service(Service.Level.PROJECT)
internal class ModuleNamesTracker(private val project: Project) : SimpleModificationTracker(), Disposable {
    /** Each file's module names when last named. */
    private val names = ConcurrentHashMap<VirtualFile, Set<String>>()

    /** The files changed since they were last named. */
    private val changed: MutableSet<VirtualFile> = ConcurrentHashMap.newKeySet()

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
                    for (event in events) {
                        if (event is VFileContentChangeEvent && !event.isFromSave &&
                            event.file.fileType is ElixirFileType
                        ) {
                            names.remove(event.file)
                            incModificationCount()
                        }
                    }
                }
            },
        )
    }

    override fun getModificationCount(): Long {
        if (changed.isNotEmpty()) {
            ThreadingAssertions.assertReadAccess()

            for (virtualFile in changed.toList()) {
                val now = virtualFile
                    .takeIf { it.isValid }
                    ?.let { PsiManager.getInstance(project).findFile(it) as? ElixirFile }
                    ?.let(::namesOf)

                changed.remove(virtualFile)

                if (now == null) {
                    names.remove(virtualFile)
                } else if (names.put(virtualFile, now) != now) {
                    incModificationCount()
                }
            }
        }

        return super.getModificationCount()
    }

    override fun dispose() {}

    /** A file's names before its first change, so that a change that keeps them doesn't move the count. */
    private fun before(event: PsiTreeChangeEvent) {
        val file = event.file as? ElixirFile ?: return
        val virtualFile = file.virtualFile ?: return

        names.computeIfAbsent(virtualFile) { namesOf(file) }
    }

    private fun after(event: PsiTreeChangeEvent) {
        val file = event.file as? ElixirFile ?: return

        file.virtualFile?.let(changed::add)
    }

    private fun namesOf(file: ElixirFile): Set<String> {
        val names = HashSet<String>()

        file.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element is StubBased<*>) {
                    ProgressManager.checkCanceled()

                    val definition = definition(element)

                    if (definition?.type == Definition.Type.MODULAR) {
                        names += Named.indexedNames(definition, element.canonicalNameSet())
                    }
                }

                super.visitElement(element)
            }
        })

        return names
    }

    companion object {
        fun getInstance(project: Project): ModuleNamesTracker = project.service()
    }
}
