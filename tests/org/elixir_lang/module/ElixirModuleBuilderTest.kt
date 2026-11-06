package org.elixir_lang.module

import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.common.runAll
import org.elixir_lang.junit.HeavyTestCase
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.SdkFixtures.fakeHome

class ElixirModuleBuilderTest : HeavyTestCase() {
    private lateinit var table: SdkFixtures.TableSnapshot

    override fun setUp() {
        super.setUp()
        table = SdkFixtures.TableSnapshot()
    }

    override fun tearDown() = runAll(
        { table.restore() },
        { super.tearDown() },
    )

    fun testTheModuleSdkCommitSetsIsTheTablesInstance() {
        val sdk = SdkFixtures.register(SdkFixtures.elixirSdk("Builder Elixir", fakeHome("elixir/builder")), testRootDisposable)
        val sdksModel = ProjectSdksModel().apply { reset(project) }
        val copy: Sdk = sdksModel.findSdk(sdk.name)!!
        val directory = FileUtil.createTempDirectory("builder", null).path
        val builder = ElixirModuleBuilder().apply {
            name = "built"
            moduleFilePath = "$directory/built.iml"
            contentEntryPath = directory
            elixirSdk = copy
        }

        val module = builder.commit(project).single()
        sdksModel.disposeUIResources()

        assertSame(sdk, ModuleRootManager.getInstance(module).sdk)
    }
}
