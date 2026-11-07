package org.elixir_lang.mix

import com.intellij.openapi.module.ModuleTypeManager
import com.intellij.testFramework.common.runAll
import org.elixir_lang.jps.shared.ElixirModuleTypeId.ELIXIR_MODULE_TYPE_ID
import org.elixir_lang.junit.LightTestCase
import org.elixir_lang.sdk.ProcessOutput

class ProjectOtpAppModuleTypeTest : LightTestCase() {
    override fun tearDown() = runAll(
        { ProcessOutput.isSmallIdeOverride = null },
        { super.tearDown() },
    )

    fun testOnIntelliJIdeaAnOtpAppModuleIsAnElixirModule() {
        ProcessOutput.isSmallIdeOverride = false

        assertEquals(ELIXIR_MODULE_TYPE_ID, Project.otpAppModuleTypeId())
    }

    fun testOnASmallIdeAnOtpAppModuleIsTheIdesDefaultType() {
        ProcessOutput.isSmallIdeOverride = true

        assertEquals(ModuleTypeManager.getInstance().defaultModuleType.id, Project.otpAppModuleTypeId())
    }
}
