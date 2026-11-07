package org.elixir_lang.mix.project

import com.intellij.projectImport.ProjectImportBuilder
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.mix.project._import.Builder

class OpenProcessorTest : PlatformTestCase() {

    fun testCanOpenProjectWithMixExsFile() {
        val projectDir = myFixture.tempDirFixture.findOrCreateDir("test_project")
        myFixture.tempDirFixture.createFile("test_project/mix.exs", MIX_EXS)
        val mixExsFile = projectDir.findChild("mix.exs")!!

        val openProcessor = OpenProcessor()

        // Test canOpenProject with the mix.exs file directly
        assertTrue("Should be able to open project with mix.exs file", openProcessor.canOpenProject(mixExsFile))
    }

    fun testCanOpenProjectWithDirectoryContainingMixExs() {
        val projectDir = myFixture.tempDirFixture.findOrCreateDir("test_project_dir")
        myFixture.tempDirFixture.createFile("test_project_dir/mix.exs", MIX_EXS)

        val openProcessor = OpenProcessor()

        // Test canOpenProject with the directory containing mix.exs
        assertTrue("Should be able to open project from directory containing mix.exs", openProcessor.canOpenProject(projectDir))
    }

    fun testCannotOpenProjectWithoutMixExs() {
        val projectDir = myFixture.tempDirFixture.findOrCreateDir("empty_project")
        myFixture.tempDirFixture.createFile("empty_project/some_file.txt", "hello")

        val openProcessor = OpenProcessor()

        // Should not be able to open a directory without mix.exs
        assertFalse("Should not be able to open project without mix.exs", openProcessor.canOpenProject(projectDir))
    }

    fun testBuilderSetProjectRootAndScan() {
        val projectDir = myFixture.tempDirFixture.findOrCreateDir("builder_test_project")
        myFixture.tempDirFixture.createFile("builder_test_project/mix.exs", MIX_EXS)

        val builder = ProjectImportBuilder.EXTENSIONS_POINT_NAME.findExtensionOrFail(Builder::class.java)

        // Set project root - this is what doQuickImport does
        builder.setProjectRoot(projectDir)

        // Get list triggers the deferred scan
        val foundApps = builder.list

        // Should find the OTP app
        assertFalse("Builder should find OTP apps after setProjectRoot and getList", foundApps.isEmpty())

        val rootApp = foundApps.find { it.name == "test_app" }
        assertNotNull("Should find the test_app OTP app", rootApp)
    }

    fun testSupportedExtensions() {
        val openProcessor = OpenProcessor()

        // Verify the supported extension is mix.exs
        assertTrue("Should support mix.exs extension", openProcessor.supportedExtensions.contains("mix.exs"))
    }

    override fun getTestDataPath(): String = "testData/org/elixir_lang/mix/project"
}
