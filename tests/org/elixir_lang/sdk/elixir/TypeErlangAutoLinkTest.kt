package org.elixir_lang.sdk.elixir

import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.common.runAll
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.mix.sync.MixSyncTestHelpers.runSuspendOnPooledThread
import org.elixir_lang.cli.getExecutableFilepathWslSafe
import org.elixir_lang.jps.shared.cli.CliTool
import org.elixir_lang.jps.shared.sdk.SdkPaths
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.SdkFixtures.codePathsUnder
import org.elixir_lang.sdk.SdkFixtures.storedErlangName
import org.elixir_lang.sdk.erlang_dependent.Asked
import org.elixir_lang.sdk.erlang_dependent.SdkAdditionalData
import org.elixir_lang.sdk.erlang_dependent.Type.Companion.ERLANG_SDK_KEY
import org.elixir_lang.sdk.erlang_dependent.elixirInstall
import org.elixir_lang.sdk.erlang_dependent.install
import java.io.File
import org.elixir_lang.sdk.erlang.Type as ErlangSdkType

/**
 * Setting up an Elixir SDK pairs it only with the Erlang SDK its creator picked or the pairing it already stores. With
 * neither, it is set up unpaired, with its own code paths, and nothing is asked.
 */
class TypeErlangAutoLinkTest : PlatformTestCase() {
    private val asked = Asked()
    private lateinit var table: SdkFixtures.TableSnapshot

    override fun setUp() {
        super.setUp()
        table = SdkFixtures.TableSnapshot()
        asked.recordMessages(testRootDisposable)
    }

    override fun tearDown() = runAll(
        { table.restore() },
        { super.tearDown() },
    )

    fun testANewSdkWithNoPairingIsSetUpUnpaired() {
        registeredErlang("Unpaired registered Erlang")
        val elixirHome = elixirInstall("1.18.4", "27")
        val elixirSdk = SdkFixtures.elixirSdk("Unpaired new Elixir", elixirHome)

        val model = resetModel()
        runSuspendOnPooledThread { ElixirSdkPathConfigurator.configure(elixirSdk, model) }

        assertNull("no Erlang SDK was picked", elixirSdk.sdkAdditionalData)
        assertNotEmpty(codePathsUnder(elixirSdk, elixirHome))
    }

    fun testAPlatformCombosDetectedPickIsAddedUnpaired() {
        registeredErlang("Detected registered Erlang")
        val model = resetModel()

        val added = addSdk(model, elixirInstall("1.18.4", "27"))

        assertNull("no Erlang SDK was picked", added.sdkAdditionalData)
        assertSame(added, model.findSdk(added.name))
        assertEmpty(asked.events)
    }

    fun testAnSdkInTheTableIsNeverPaired() {
        registeredErlang("Table registered Erlang")
        val elixirHome = elixirInstall("1.18.4", "27")
        val elixirSdk = SdkFixtures.register(SdkFixtures.elixirSdk("Table unpaired Elixir", elixirHome))

        runSuspendOnPooledThread { Type.instance.setupSdkPaths(elixirSdk) }

        assertNull("no Erlang SDK was picked", storedErlangName(elixirSdk))
        val roots = elixirSdk.rootProvider.getFiles(OrderRootType.CLASSES).map { it.path }
        assertNotEmpty(roots)
        assertEquals("only the Elixir install's code paths", roots, codePathsUnder(elixirSdk, elixirHome))
    }

    fun testACustomCreatedSdkPairsThePick() {
        registeredErlang("Custom unpicked Erlang")
        val picked = registeredErlang("Custom picked Erlang")
        val elixirSdk = SdkFixtures.elixirSdk("Custom Elixir", elixirInstall("1.18.4", "27"))
        elixirSdk.putUserData(ERLANG_SDK_KEY, picked)

        val model = resetModel()
        runSuspendOnPooledThread { ElixirSdkPathConfigurator.configure(elixirSdk, model) }

        assertEquals(picked.name, (elixirSdk.sdkAdditionalData as SdkAdditionalData).getErlangSdkName())
    }

    /** The mise prompt's precondition: a mise Elixir home, a mise Erlang install, and no Erlang SDK registered. */
    fun testAMiseHomeWithNoErlangRegisteredIsSetUpUnpaired() {
        val userHome = FileUtil.createTempDirectory("mise_user_home", null, true)
        val mise = File(userHome, SdkPaths.MISE_POSIX_PATH_FROM_HOME)
        miseErlangInstall(File(mise, "erlang/27.3.4"))
        val elixirHome = File(mise, "elixir/1.18.4-otp-27").path
        File(elixirHome, "lib/elixir/ebin").apply { check(mkdirs()) }.resolve("elixir.app")
            .writeText("{application,elixir,\n [{description,\"elixir\"},\n  {vsn,\"1.18.4\"}]}.\n")
        assertEmpty("precondition: no Erlang SDK is registered", ProjectJdkTable.getInstance().getSdksOfType(ErlangSdkType.instance))

        lateinit var added: Sdk
        PlatformTestUtil.withSystemProperty<Throwable>("user.home", userHome.path) { added = addSdk(resetModel(), elixirHome) }

        assertEmpty("no dialog is shown", asked.events)
        assertNull("no Erlang SDK was picked", added.sdkAdditionalData)
    }

    /** A mise Erlang install that `isValidSdkHome` accepts, its OTP version written but not read. */
    private fun miseErlangInstall(home: File) {
        File(home, "releases/27").apply { check(mkdirs()) }.resolve("OTP_VERSION").writeText("27.3.4\n")
        File(CliTool.ERL.getExecutableFilepathWslSafe(home.path)).let { erl ->
            check(erl.parentFile.mkdirs() && erl.createNewFile() && erl.setExecutable(true)) { "Failed to create $erl" }
        }
    }

    private fun registeredErlang(name: String): Sdk = SdkFixtures.register(SdkFixtures.erlangSdk(name, install("27", "27.3.4")))

    /** The model a settings dialog or an SDK combo resets from the table. */
    private fun resetModel(): ProjectSdksModel =
        ProjectSdksModel().apply {
            reset(project)
            Disposer.register(testRootDisposable) { disposeUIResources() }
        }

    /** A detected home picked in a platform SDK combo: the SDK handed to the combo's callback. */
    private fun addSdk(model: ProjectSdksModel, home: String): Sdk {
        var added: Sdk? = null
        model.addSdk(Type.instance, home) { added = it }
        return added ?: throw AssertionError("the callback was handed no Elixir SDK")
    }
}
