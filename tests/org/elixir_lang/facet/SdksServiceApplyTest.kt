package org.elixir_lang.facet

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.testFramework.registerOrReplaceServiceInstance
import com.intellij.testFramework.runInEdtAndGet
import com.intellij.testFramework.runInEdtAndWait
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.sdk.ProcessOutput
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.elixir.ElixirSdkMutation
import org.elixir_lang.sdk.wsl.MockWslCompatService
import org.elixir_lang.sdk.wsl.WslCompatService
import org.elixir_lang.util.WriteActions
import java.util.concurrent.Callable

class SdksServiceApplyTest : PlatformTestCase() {
    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        ProcessOutput.isSmallIdeOverride = true
        ApplicationManager.getApplication().registerOrReplaceServiceInstance(
            WslCompatService::class.java,
            MockWslCompatService(),
            testRootDisposable,
        )
        SdksService.getInstance()!!.resetForTests()
    }

    override fun tearDown() {
        try {
            ProcessOutput.isSmallIdeOverride = null
            SdksService.getInstance()!!.resetForTests()
        } finally {
            super.tearDown()
        }
    }

    /** A Settings tab's Save Changes applies on the EDT holding no lock, where the modal dialog's Apply holds write-intent. */
    fun testSavingFromASettingsTabKeepsThePairing() {
        val e1Home = SdkFixtures.erlangHomeWithEbin("26", "26.2.5")
        val e2Home = SdkFixtures.erlangHomeWithEbin("27", "27.3.4")
        val xHome = SdkFixtures.elixirHome("1.18.4")
        val model = runInEdtAndGet {
            val e1 = register(SdkFixtures.erlangSdk(E1, e1Home))
            register(SdkFixtures.erlangSdk(E2, e2Home))
            val x = register(SdkFixtures.elixirSdk(X, xHome))
            SdkFixtures.commitPairing(x, e1)
            SdksService.getInstance()!!.resetForTests()
            SdksService.getInstance()!!.getModel().also { model ->
                // As the SDKs page's Erlang combo applies a new pairing to the model's copy.
                WriteActions.runWriteAction {
                    ElixirSdkMutation.applyDependencySelection(
                        elixirSdk = model.findSdk(X)!!,
                        erlangSdk = model.findSdk(E2),
                        suppressOtpMismatchWarning = false,
                    )
                }
            }
        }

        runInEdtAndWait(writeIntent = false) {
            assertFalse("a Settings tab's click holds no lock", ApplicationManager.getApplication().isReadAccessAllowed)
            SdksService.getInstance()!!.apply(model)
        }

        val stored = ReadAction.nonBlocking(Callable {
            SdkFixtures.persistedElixirData(ProjectJdkTable.getInstance().findJdk(X)!!)?.getErlangSdkName()
        }).executeSynchronously()
        assertEquals(E2, stored)
    }

    private fun register(sdk: Sdk): Sdk = SdkFixtures.register(sdk, testRootDisposable)

    private companion object {
        const val E1 = "Tab Save Erlang 1"
        const val E2 = "Tab Save Erlang 2"
        const val X = "Tab Save Elixir"
    }
}
