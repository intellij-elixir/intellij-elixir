package org.elixir_lang.facet

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.testFramework.registerOrReplaceServiceInstance
import com.intellij.testFramework.runInEdtAndWait
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.facet.sdks.SdksPage
import org.elixir_lang.sdk.ProcessOutput
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.elixir.ElixirSdkMutation
import org.elixir_lang.sdk.erlang_dependent.SdkAdditionalData
import org.elixir_lang.sdk.erlang_dependent.elixirInstall
import org.elixir_lang.sdk.wsl.MockWslCompatService
import org.elixir_lang.sdk.wsl.WslCompatService
import org.elixir_lang.util.WriteActions
import java.util.concurrent.Callable
import org.elixir_lang.facet.sdks.elixir.Configurable as ElixirSdksConfigurable

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
        runInEdtAndWait {
            val e1 = register(SdkFixtures.erlangSdk(E1, e1Home))
            register(SdkFixtures.erlangSdk(E2, e2Home))
            val x = register(SdkFixtures.elixirSdk(X, xHome))
            SdkFixtures.commitPairing(x, e1)
            SdksService.getInstance()!!.resetForTests()
            SdksService.getInstance()!!.getModel().let { model ->
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
            SdksService.getInstance()!!.apply()
        }

        val stored = ReadAction.nonBlocking(Callable {
            SdkFixtures.persistedElixirData(ProjectJdkTable.getInstance().findJdk(X)!!)?.getErlangSdkName()
        }).executeSynchronously()
        assertEquals(E2, stored)
    }

    /**
     * A Settings tab closes on the EDT holding no lock, where the modal dialog's Cancel holds write-intent: closed after its
     * Apply was refused, it refreshes from the table the edit that Apply left in the shared copy.
     */
    fun testClosingASettingsTabRefreshesWhatItsRefusedApplyLeft() {
        val xHome = elixirInstall("1.18.4", "27")
        lateinit var x: Sdk
        lateinit var page: SdksPage
        runInEdtAndWait {
            x = register(SdkFixtures.elixirSdk(X, xHome)).also { SdkFixtures.commit(it, SdkAdditionalData(it)) }
            SdksService.getInstance()!!.resetForTests()
            page = SdksPage(ElixirSdksConfigurable(), mutableListOf())
            page.rename(x, RENAMED)
            assertThrows(ConfigurationException::class.java) { page.apply() }
        }

        runInEdtAndWait(writeIntent = false) {
            assertFalse("a Settings tab's close holds no lock", ApplicationManager.getApplication().isReadAccessAllowed)
            page.cancel()
        }

        runInEdtAndWait { assertEquals(X, SdksPage.copy(x).name) }
    }

    private fun register(sdk: Sdk): Sdk = SdkFixtures.register(sdk, testRootDisposable)

    private companion object {
        const val E1 = "Tab Save Erlang 1"
        const val E2 = "Tab Save Erlang 2"
        const val X = "Tab Save Elixir"
        const val RENAMED = "Tab Save Renamed"
    }
}
