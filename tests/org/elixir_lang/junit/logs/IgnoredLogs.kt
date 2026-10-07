package org.elixir_lang.junit.logs

/**
 * Platform logs no plugin change can prevent. A plugin logger never belongs here: a test that exercises a plugin
 * warning or error expects it with [expectWarnings] or [expectErrors].
 */
internal object IgnoredLogs {
    /**
     * [silent] keeps it out of the console and `idea.log` as well, for noise a test provokes on purpose. [frame],
     * `<class>.<method>`, narrows it to a throwable, or one of its causes, thrown through that method.
     */
    private class Ignored(val category: String, val message: Regex, val silent: Boolean = false, val frame: String? = null)

    private const val TEST_DISTRIBUTION = "IntellijElixirWSLDistribution"
    private const val LOCAL_HISTORY_FLUSH = "com.intellij.history.core.ChangeListImpl.flush"
    private const val PROJECT_ENTITY_REMOVAL = "com.intellij.platform.project.ProjectEntityKt.asEntityOrNull"
    private const val PROJECT_ENTITY_BACKEND_REMOVAL =
        "com.intellij.platform.project.backend.BackendProjectEntitiesStorage.removeProjectEntity"

    private val IGNORED = listOf(
        // Headless tests have no look and feel to supply it.
        Ignored("#com.intellij.util.ui.StyleSheetUtil", Regex("^Missing global CSS sheet")),
        // The platform's own parameter info popup creates its alarm without a coroutine scope.
        Ignored("#com.intellij.util.Alarm", Regex("^Do not create alarm without coroutineScope: .*ParameterInfoControllerBase")),
        // Timing: a platform listener or action update ran past its threshold on a loaded machine.
        Ignored(
            "#com.intellij.workspaceModel.ide.impl.WorkspaceModelMessageDeliveryListener",
            Regex("^Long WSM event processing"),
        ),
        Ignored("#com.intellij.openapi.actionSystem.impl.Utils", Regex("^\\d+ ms to runUpdateSessionForInputEvent")),
        // A test JVM whose sandbox VFS a crashed or killed earlier run left damaged rebuilds it on start.
        Ignored("#com.intellij.openapi.vfs.newvfs.persistent.PersistentFSLoader", Regex("^\\[VFS load problem]: ")),
        Ignored(
            "#com.intellij.openapi.vfs.newvfs.persistent.PersistentFSConnector",
            Regex("^Filesystem storage is corrupted or does not exist\\. \\[Re]Building\\."),
        ),
        // The WSL tests' distribution exists on no machine, so the platform's read of an SDK home registered in it
        // fails; once per JVM, as the VFS remembers the miss. `\\wsl$` only: Windows takes far longer to fail
        // a `\\wsl.localhost` path, so a test reading one should fail rather than stall silently.
        Ignored(
            "#com.intellij.openapi.vfs.impl.local.LocalFileSystemBase",
            Regex("""^\\\\wsl\$\\$TEST_DISTRIBUTION\\"""),
            silent = true,
        ),
        // CI runs headless, where the platform's registry disables JCEF, which the Markdown plugin's settings ask about.
        Ignored("#com.intellij.ui.jcef.JBCefApp", Regex("^JCEF is manually disabled in headless env")),
        // 2026.2 checks the product's environment-configured modules against a plugin set that tests do not complete.
        Ignored(
            "#com.intellij.ide.plugins.PluginManager",
            Regex("""^Environment-configured module is not found: intellij\.platform\.split$"""),
        ),
        // IJPL-248209, fixed in 2026.3: 2026.2's local-history flusher reads a setting every second through the global
        // application, which a mock-application `ParsingTestCase` has replaced. Remove once the since-build is 2026.3.
        Ignored(
            "#com.intellij.openapi.application.impl.ExceptionsKt",
            Regex("^Unhandled exception in "),
            frame = LOCAL_HISTORY_FLUSH,
        ),
        Ignored("java.lang.NullPointerException", Regex(""), frame = LOCAL_HISTORY_FLUSH),
        Ignored("java.lang.IllegalArgumentException", Regex(""), frame = LOCAL_HISTORY_FLUSH),
        // A project disposed before a `ParsingTestCase` swaps in its mock application is removed from the kernel
        // database afterwards, through whichever application is then installed. A mock application has no
        // `KernelService`, so the removal can also fail with a null `getService`.
        Ignored("#fleet.kernel.Transactor", Regex("^Kernel@\\S+ change has failed"), frame = PROJECT_ENTITY_REMOVAL),
        Ignored(
            "#com.intellij.openapi.application.impl.ExceptionsKt",
            Regex("^Unhandled exception in "),
            frame = PROJECT_ENTITY_REMOVAL,
        ),
        Ignored("com.intellij.util.pico.PicoIntrospectionException", Regex(""), frame = PROJECT_ENTITY_REMOVAL),
        Ignored(
            "#com.intellij.openapi.application.impl.ExceptionsKt",
            Regex("^Unhandled exception in "),
            frame = PROJECT_ENTITY_BACKEND_REMOVAL,
        ),
        Ignored(
            "java.lang.NullPointerException",
            Regex("""^getService\(\.\.\.\) must not be null"""),
            frame = PROJECT_ENTITY_BACKEND_REMOVAL,
        ),
    )

    fun matches(category: String, message: String, t: Throwable?): Boolean = find(category, message, t) != null

    fun isSilent(category: String, message: String, t: Throwable?): Boolean = find(category, message, t)?.silent == true

    private fun find(category: String, message: String, t: Throwable?): Ignored? =
        IGNORED.firstOrNull {
            it.category == category && it.message.containsMatchIn(message) && (it.frame == null || thrownThrough(t, it.frame))
        }

    private fun thrownThrough(t: Throwable?, frame: String): Boolean =
        generateSequence(t) { it.cause }.any { throwable ->
            throwable.stackTrace.any { "${it.className}.${it.methodName}" == frame }
        }
}
