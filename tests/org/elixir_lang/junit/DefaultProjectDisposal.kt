package org.elixir_lang.junit

import com.intellij.mock.MockApplication
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.ComponentManagerEx
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.impl.ProjectManagerImpl
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testFramework.ParsingTestCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.platform.engine.support.descriptor.ClassSource
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import kotlin.time.Duration.Companion.seconds

/**
 * Settles the real application before a [ParsingTestCase] class installs its [MockApplication], which anything the real
 * application still runs then reaches through `ApplicationManager`. Disposes the default project an earlier class
 * left, as the platform disposes an idle one on a JVM-wide timer, closes the shared light project, and waits for the
 * application's cancelled coroutines.
 * A project's removal from the platform's kernel database can still run later; `IgnoredLogs` covers that.
 */
@Suppress("UnstableApiUsage") // The platform's own test-only disposal; nothing public disposes the default project.
class DefaultProjectDisposal : TestExecutionListener {
    override fun executionStarted(testIdentifier: TestIdentifier) {
        val testClass = (testIdentifier.source.orElse(null) as? ClassSource)?.getJavaClass() ?: return
        if (!testIdentifier.isContainer || !ParsingTestCase::class.java.isAssignableFrom(testClass)) return

        val application = ApplicationManager.getApplication() ?: return
        if (application is MockApplication) return

        disposeDefaultProject(application)
        closeLightProject(application)
        awaitCancelledCoroutines(application)
    }

    private fun disposeDefaultProject(application: Application) {
        val projectManager = ProjectManager.getInstanceIfCreated() as? ProjectManagerImpl ?: return
        if (!projectManager.isDefaultProjectInitialized) return

        // On the EDT the platform disposes it at once instead of queueing the disposal.
        application.invokeAndWait(
            { projectManager.disposeDefaultProjectAndCleanupComponentsForDynamicPluginTests() },
            ModalityState.any(),
        )
    }

    // `ParsingTestCase.setUp` would close it and then hold the EDT, which its cancelled coroutines need to finish.
    @Suppress("DEPRECATION") // The class is; its static close, which `ParsingTestCase` calls too, has no replacement.
    private fun closeLightProject(application: Application) {
        application.invokeAndWait(LightPlatformTestCase::closeAndDeleteProject, ModalityState.any())
    }

    // Disposal only cancels a scope; what it runs on cancellation finishes afterwards.
    private fun awaitCancelledCoroutines(application: Application) {
        val finishing = (application as ComponentManagerEx).getCoroutineScope().coroutineContext.job.cancelledDescendants()
        if (finishing.isEmpty()) return
        // Logged rather than thrown: the launcher only warns about a listener that throws, which no report shows.
        try {
            runBlocking { withTimeout(TIMEOUT) { finishing.joinAll() } }
        } catch (_: TimeoutCancellationException) {
            LOG.error("${finishing.size} cancelled coroutine(s) still running $TIMEOUT before a mock-application class")
        }
    }

    private fun Job.cancelledDescendants(): List<Job> =
        children.flatMap { child ->
            if (child.isCancelled && !child.isCompleted) listOf(child) else child.cancelledDescendants()
        }.toList()

    private companion object {
        val TIMEOUT = 10.seconds

        // Created on use: a logger created before the test environment is set up is the platform's default one, whose
        // `error` throws, and this class is loaded before that.
        val LOG: Logger
            get() = logger<DefaultProjectDisposal>()
    }
}
