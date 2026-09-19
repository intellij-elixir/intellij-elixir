package org.elixir_lang.sdk

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.DumbModeTask
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.impl.FilePropertyPusher
import com.intellij.openapi.roots.impl.PushedFilePropertiesUpdater
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresReadLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.elixir_lang.isElixirModule
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class ElixirLanguageLevelStoreListener(private val project: Project) : SdkVersionsListener {
    override fun sdkVersionsChanged(homePaths: Set<String>, canonicalHome: String) {
        if (!project.isDisposed) project.service<ElixirLanguageLevelPushes>().request()
    }
}

@Service(Service.Level.PROJECT)
internal class ElixirLanguageLevelPushes(private val project: Project, private val scope: CoroutineScope) {
    private val requested = AtomicBoolean(false)
    private val pushes = AtomicInteger()
    private val settled = AtomicInteger()

    /** `requested` is cleared before the check, so a change landing while it runs still schedules another. */
    fun request() {
        if (!requested.compareAndSet(false, true)) return

        scope.launch {
            requested.set(false)
            // Smart first: `pushAll` drops a request made before the project's first scan.
            if (smartReadAction(project) { isStale() } || unfinishedPushPending()) push()
        }
    }

    /** Until this session's own push settles, the flag it set says only that the push is still running. */
    fun unfinishedPushPending(): Boolean =
        settled.get() == pushes.get() && PropertiesComponent.getInstance(project).getBoolean(PUSH_PENDING)

    fun push() {
        val push = pushes.incrementAndGet()
        PropertiesComponent.getInstance(project).setValue(PUSH_PENDING, true)
        @Suppress("UnstableApiUsage")
        PushedFilePropertiesUpdater.getInstance(project)
            .pushAll(FilePropertyPusher.EP_NAME.findExtensionOrFail(ElixirVersionPusher::class.java))
        // A push reports no end, and one cut short leaves roots that match over directories that do not, which `isStale`
        // cannot see. The push can be merged or re-queued behind this task, but the project stays dumb until it has run.
        DumbService.getInstance(project).queueTask(object : DumbModeTask() {
            override fun performInDumbMode(indicator: ProgressIndicator) {
                clearPendingOnceSmart(push)
            }
        })
    }

    @get:TestOnly
    val lastPush: Int get() = pushes.get()

    /** A later push can set the flag before smart mode reaches this one's callback, and has not run yet. */
    fun clearPendingOnceSmart(push: Int) {
        DumbService.getInstance(project).runWhenSmart {
            settled.accumulateAndGet(push, ::maxOf)
            if (pushes.get() == push) PropertiesComponent.getInstance(project).unsetValue(PUSH_PENDING)
        }
    }

    companion object {
        const val PUSH_PENDING = "elixir.language.level.push.pending"
    }

    @RequiresReadLock
    @Suppress("UnstableApiUsage")
    fun isStale(): Boolean {
        ThreadingAssertions.assertReadAccess()

        return ModuleManager.getInstance(project).modules
            .filter { module -> module.isElixirModule() }
            .any { module ->
                val version = ElixirVersionPusher.versionOf(module) ?: return@any false
                ModuleRootManager.getInstance(module).contentRoots
                    .any { root -> ElixirVersionPusher.KEY.getPersistentValue(root) != version }
            }
    }
}
