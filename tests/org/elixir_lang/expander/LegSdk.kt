package org.elixir_lang.expander

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.testFramework.IndexingTestUtil
import org.elixir_lang.elixir_surface.LegManifest
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.SdkVersionsStore
import org.elixir_lang.sdk.erlang_dependent.SdkAdditionalData
import org.elixir_lang.sdk.Type as SdkRoots

/** The leg's Erlang and Elixir as [Module]'s SDK, with the Elixir SDK's code paths as its class roots. */
internal object LegSdk {
    const val ERLANG = "leg Erlang"

    fun setUp(module: Module, disposable: Disposable): Sdk {
        val erlangHome = LegManifest.environment("ERLANG_SDK_HOME")
        val elixirHome = LegManifest.environment("ELIXIR_LANG_ELIXIR_PATH")
        val erlang = SdkFixtures.registerAndWaitForFill(SdkFixtures.erlangSdk(ERLANG, erlangHome), disposable)
        val elixir = SdkFixtures.registerAndWaitForFill(SdkFixtures.elixirSdk("leg", elixirHome), disposable)
        WriteAction.runAndWait<Throwable> {
            elixir.sdkModificator.apply {
                SdkRoots.addCodePaths(this)
                commitChanges()
            }
        }
        SdkFixtures.commit(elixir, SdkAdditionalData(erlang, elixir))
        SdkFixtures.waitUntil("the store reads the leg's homes", 30_000) {
            SdkVersionsStore.getInstance().elixirVersions(elixirHome) != null &&
                SdkVersionsStore.getInstance().otpRelease(erlangHome) != null
        }
        ModuleRootModificationUtil.setModuleSdk(module, elixir)
        IndexingTestUtil.waitUntilIndexesAreReady(module.project)

        return elixir
    }

    fun tearDown(module: Module) {
        ModuleRootModificationUtil.setModuleSdk(module, null)
        SdkVersionsStore.getInstance().clearForTests()
    }
}
