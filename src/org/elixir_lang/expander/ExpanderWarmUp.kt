package org.elixir_lang.expander

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.declaration.expanderAnswersChanged
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.util.ElixirProjectDisposable
import org.jetbrains.annotations.TestOnly
import org.jetbrains.concurrency.CancellablePromise
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Answers an ask from what [ProjectModules] holds, and where it holds nothing yet, expands it in the background, so the
 * ask after it finds it: a warm-up that finds more has what references resolved dropped and the editors analysed again,
 * once for the warm-ups queued before it. In unit-test mode the expansion runs first, on the asking thread, unless
 * inside [asynchronous] or where [ExpanderThreads.mayExpand] is false.
 */
@Service(Service.Level.PROJECT)
internal class ExpanderWarmUp(private val project: Project) {
    private val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("Elixir expander warm-up", 1)

    /** The last warm-up started. */
    @get:TestOnly
    @Volatile
    var pending: CancellablePromise<*>? = null
        private set

    /** The key of each warm-up queued or running. */
    private val warming = ConcurrentHashMap.newKeySet<Any>()

    private val rereadQueued = AtomicBoolean()

    /** Whether a [declined] re-read waits on the EDT. */
    private val declinedQueued = AtomicBoolean()

    @Volatile
    private var asynchronous = false

    private val holder: ProjectModules get() = ProjectModules.getInstance(project)

    /** [atom] as [ProjectModules.locateHeld] locates it for [asker], or `null` where it isn't held yet. */
    @RequiresReadLock
    fun locateHeld(asker: PsiFile, atom: String, inputs: ProjectModules.Inputs): ProjectModules.Located? =
        heldOrWarm(Triple(atom, asker, inputs), { holder.locateHeld(asker, atom, inputs) }) {
            if (asker.isValid) holder.warm(asker, atom, inputs)
        }

    /** [several]'s copies of [atom] as [ProjectModules.locateEachHeld] locates them for [asker], `null` for one not held yet. */
    @RequiresReadLock
    fun locateEachHeld(
        asker: PsiFile,
        several: ProjectModules.Located.Several,
        atom: String,
        inputs: ProjectModules.Inputs,
    ): List<ProjectModules.Located?> =
        heldOrWarm(Triple(several.copies, asker, inputs), { holder.locateEachHeld(asker, several, atom, inputs) }, { it.count { located -> located == null } }) {
            if (asker.isValid) holder.warmEach(asker, several, atom, inputs)
        }

    /** [file]'s index as [ProjectModules.indexedHeld] gives it, or `null` where it isn't held yet. */
    @RequiresReadLock
    fun indexedHeld(file: ElixirFile, inputs: ProjectModules.Inputs): ProjectModules.Indexed? =
        heldOrWarm(file to inputs, { holder.indexedHeld(file, inputs) }) {
            if (file.isValid) holder.indexed(file, inputs)
        }

    /** [file]'s index: computed where [ExpanderThreads.mayExpand] holds, else as [indexedHeld] gives it. */
    @RequiresReadLock
    fun indexed(file: ElixirFile, inputs: ProjectModules.Inputs): ProjectModules.Indexed? =
        if (ExpanderThreads.mayExpand()) holder.indexed(file, inputs) else indexedHeld(file, inputs)

    /**
     * The config files [ProjectModules.reachedConfigFiles] gives for [inputs] where [ExpanderThreads.mayExpand] holds, else
     * as [reachedConfigFilesHeld] gives them.
     */
    @RequiresReadLock
    fun reachedConfigFiles(inputs: ProjectModules.Inputs): Set<VirtualFile>? =
        if (ExpanderThreads.mayExpand()) holder.reachedConfigFiles(inputs) else reachedConfigFilesHeld(inputs)

    /** The config files [ProjectModules.reachedConfigFilesHeld] gives for [inputs], or `null` where a table isn't held yet. */
    @RequiresReadLock
    private fun reachedConfigFilesHeld(inputs: ProjectModules.Inputs): Set<VirtualFile>? =
        heldOrWarm(inputs, { holder.reachedConfigFilesHeld(inputs) }) { holder.reachedConfigFiles(inputs) }

    /**
     * One re-read, once the gesture on the EDT that asked has ended and after the warm-ups queued before it, for an answer
     * the expander gave none of because this thread may not expand; it warms nothing.
     */
    fun declined() {
        if (!declinedQueued.compareAndSet(false, true)) return

        ApplicationManager.getApplication().invokeLater({
            declinedQueued.set(false)
            rereadAfterTheQueue()
        }, project.disposed)
    }

    /**
     * [clause]'s `.beam`'s mirror where it is built already, else `null`; the warm-up builds it, and places [clause] in it
     * where the `.beam` is still as [clause] read it.
     */
    @RequiresReadLock
    fun builtMirror(clause: CompiledClause): ElixirFile? =
        heldOrWarm(clause.beam, { holder.builtMirror(clause.beam) }) {
            holder.mirror(clause.beam)
            holder.placed(clause)
        }

    /**
     * [held]'s answer, and where it is [missing] any part, [warm] started unless one for [key] is queued or running
     * already, restarted after a write as any non-blocking read is. A [key] naming an asker turns away only that
     * asker's asks, so a warm-up whose asker is gone before it runs leaves no other asker unwarmed.
     */
    private fun <T> heldOrWarm(key: Any, held: () -> T, missing: (T) -> Int = { if (it == null) 1 else 0 }, warm: () -> Unit): T {
        val answer = held()
        val missed = missing(answer)
        if (missed == 0) return answer

        if (ApplicationManager.getApplication().isUnitTestMode && !asynchronous && ExpanderThreads.mayExpand()) {
            warm()

            return held()
        }

        if (!warming.add(key)) return answer

        pending = ReadAction.nonBlocking(Callable {
            warm()
            if (missing(held()) < missed) rereadAfterTheQueue()
        })
            .inSmartMode(project)
            .expireWith(ElixirProjectDisposable.getInstance(project))
            .submit(executor)
            .onProcessed { warming.remove(key) }

        return answer
    }

    /** One re-read, after the warm-ups queued before it, however many of them found more. */
    private fun rereadAfterTheQueue() {
        if (!rereadQueued.compareAndSet(false, true)) return

        executor.submit {
            rereadQueued.set(false)
            expanderAnswersChanged(project, "the expander warmed what an ask found missing")
        }
    }

    /** Done once every warm-up and re-read queued before it has run. */
    @TestOnly
    fun drained(): Future<*> = executor.submit {}

    /** Runs [block] with each warm-up it starts left to the background, as outside unit-test mode. */
    @TestOnly
    fun <T> asynchronous(block: () -> T): T {
        asynchronous = true

        return try {
            block()
        } finally {
            asynchronous = false
        }
    }

    companion object {
        fun getInstance(project: Project): ExpanderWarmUp = project.service()
    }
}
