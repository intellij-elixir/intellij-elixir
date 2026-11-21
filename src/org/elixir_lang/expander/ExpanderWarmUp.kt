package org.elixir_lang.expander

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.util.ElixirProjectDisposable
import org.jetbrains.annotations.TestOnly
import org.jetbrains.concurrency.CancellablePromise
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap

/**
 * Answers an ask from what [ProjectModules] holds, and where it holds nothing yet, expands it in the background, so the
 * ask after it finds it. In unit-test mode the expansion runs first, on the asking thread, unless inside [asynchronous].
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

    @Volatile
    private var asynchronous = false

    /** [atom] as [ProjectModules.locateHeld] locates it for [asker], or `null` where it isn't held yet. */
    @RequiresReadLock
    fun locateHeld(asker: PsiFile, atom: String, inputs: ProjectModules.Inputs): ProjectModules.Located? {
        val holder = ProjectModules.getInstance(project)

        return heldOrWarm(Triple(atom, asker, inputs), { holder.locateHeld(asker, atom, inputs) }) {
            if (asker.isValid) holder.warm(asker, atom, inputs)
        }
    }

    /**
     * [held]'s answer, or where it has none, [warm] started unless one for [key] is queued or running already, restarted
     * after a write as any non-blocking read is. A [key] naming an asker turns away only that asker's asks,
     * so a warm-up whose asker is gone before it runs leaves no other asker unwarmed.
     */
    private fun <T : Any> heldOrWarm(key: Any, held: () -> T?, warm: () -> Unit): T? {
        held()?.let { return it }

        if (ApplicationManager.getApplication().isUnitTestMode && !asynchronous) {
            warm()

            return held()
        }

        if (!warming.add(key)) return null

        pending = ReadAction.nonBlocking(Callable { warm() })
            .inSmartMode(project)
            .expireWith(ElixirProjectDisposable.getInstance(project))
            .submit(executor)
            .onProcessed { warming.remove(key) }

        return null
    }

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
