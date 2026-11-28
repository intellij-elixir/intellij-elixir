package org.elixir_lang.junit

import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleTypeManager
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.util.ThrowableRunnable
import org.elixir_lang.jps.shared.ElixirModuleTypeId.ELIXIR_MODULE_TYPE_ID
import org.elixir_lang.junit.logs.UnexpectedLogs

/** A [HeavyPlatformTestCase] failed for any warning, error or uncaught exception it does not expect. */
abstract class HeavyTestCase : HeavyPlatformTestCase() {
    @Throws(Throwable::class)
    override fun runBare(testRunnable: ThrowableRunnable<Throwable>) {
        UnexpectedLogs.failOnUnexpectedLogs { super.runBare(testRunnable) }
    }

    /** A new `ELIXIR_MODULE` named [name] in the test's project. */
    protected fun elixirModule(name: String): Module =
        doCreateRealModuleIn(name, project, ModuleTypeManager.getInstance().findByID(ELIXIR_MODULE_TYPE_ID))
}
