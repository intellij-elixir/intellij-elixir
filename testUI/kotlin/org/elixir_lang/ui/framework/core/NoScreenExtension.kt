package org.elixir_lang.ui.framework.core

import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * Puts every `testUI` test behind a [NoScreenGuard]. Registered by `META-INF/services`, with autodetection turned on in
 * `junit-platform.properties`, so no test can leave it out.
 */
class NoScreenExtension : BeforeEachCallback, AfterEachCallback {
    override fun beforeEach(context: ExtensionContext) {
        val name = "${context.requiredTestClass.simpleName}.${context.requiredTestMethod.name}".replace(Regex("[^A-Za-z0-9._-]"), "_")
        context.getStore(NAMESPACE).put(GUARD, NoScreenGuard.start(name, IdeTestContext.testsDirectory))
    }

    override fun afterEach(context: ExtensionContext) {
        context.getStore(NAMESPACE).remove(GUARD, NoScreenGuard::class.java)?.verify()
    }

    private companion object {
        val NAMESPACE: ExtensionContext.Namespace = ExtensionContext.Namespace.create(NoScreenExtension::class.java)
        const val GUARD = "guard"
    }
}
