package org.elixir_lang.expander

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.testFramework.IndexingTestUtil
import org.elixir_lang.psi.scope.WalkTestSupport
import org.elixir_lang.sdk.elixir.ElixirSdkPathConfigurator

/** Attaches to [module]'s SDK, the leg's Elixir SDK, the sources `ElixirSdkPathConfigurator` attaches. */
internal fun attachSdkSources(module: Module) {
    val sdk = ModuleRootManager.getInstance(module).sdk ?: error("$module has no SDK")
    WriteAction.runAndWait<Throwable> {
        sdk.sdkModificator.apply {
            ElixirSdkPathConfigurator.addSourcePaths(this)
            commitChanges()
        }
    }
    IndexingTestUtil.waitUntilIndexesAreReady(module.project)
}

/** The leg's OTP `stdlib` `ebin` as a library of [module], with no source, readable until [disposable] is disposed. */
internal fun addOtpStdlib(module: Module, disposable: Disposable) {
    WalkTestSupport.addLibrary(module.project, module, disposable, "otp-stdlib", stdlibEbin())
}
