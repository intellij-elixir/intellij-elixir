package org.elixir_lang.junit.logs

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import org.elixir_lang.junit.LightTestCase

/** The platform's warning that a class initializer asked for a service, as the current platform words it. */
class ClassInitializerServiceRequestTest : LightTestCase() {
    // One method: the class initializer runs, and so warns, once per JVM.
    fun testOnlyAPluginClassAskingForAServiceInItsClassInitializerFailsItsTest() {
        val warning = expectWarnings(UnexpectedLogs.COMPONENT_MANAGER_CATEGORY, Regex(" <clinit> requests ")) {
            AsksForAServiceInItsInitializer.load()
        }.single()
        val platformWarning = warning.replace(AsksForAServiceInItsInitializer::class.java.name, "com.intellij.openapi.Cache")

        assertTrue(logged(warning).fails)
        assertFalse(logged(platformWarning).fails)
    }

    private fun logged(message: String): UnexpectedLogs.Logged =
        UnexpectedLogs.Logged(
            test = null,
            testName = null,
            thread = Thread.currentThread().name,
            level = UnexpectedLogs.Level.WARN,
            category = UnexpectedLogs.COMPONENT_MANAGER_CATEGORY,
            message = message,
            cause = null,
            frames = emptyList(),
        )
}

@Service
internal class RequestedByAClassInitializer

private object AsksForAServiceInItsInitializer {
    init {
        service<RequestedByAClassInitializer>()
    }

    fun load() = Unit
}
