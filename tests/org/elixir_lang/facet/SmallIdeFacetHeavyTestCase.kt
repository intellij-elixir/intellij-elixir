package org.elixir_lang.facet

import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.common.runAll
import com.intellij.testFramework.runInEdtAndWait
import org.elixir_lang.junit.HeavyTestCase
import org.elixir_lang.sdk.ProcessOutput
import java.io.File

/** A heavy test on a small IDE, whose Elixir SDKs come from [models] and are removed after it. */
abstract class SmallIdeFacetHeavyTestCase : HeavyTestCase() {
    internal val models = FacetModelFixtures()

    override fun setUp() {
        super.setUp()
        ProcessOutput.isSmallIdeOverride = true
    }

    override fun tearDown() {
        runAll(
            { runInEdtAndWait { models.leaveSmallIde(project) } },
            { super.tearDown() },
        )
    }

    // Only Module.getModuleFilePath names the .iml.
    @Suppress("UnstableApiUsage")
    protected fun savedModuleFile(): String {
        PlatformTestUtil.saveProject(project, true)
        return FileUtil.loadFile(File(module.moduleFilePath))
    }
}
