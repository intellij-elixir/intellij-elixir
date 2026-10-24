package org.elixir_lang.tool_manager

import com.intellij.testFramework.PlatformTestUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.tool_manager.mise.MiseToolManager
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ToolManagerSettingsStorageTest : PlatformTestCase() {
    private lateinit var projectDir: Path

    override fun setUp() {
        super.setUp()
        projectDir = Files.createTempDirectory("tool-manager-settings").toRealPath()
    }

    override fun tearDown() {
        try {
            projectDir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testTheOptInSavedForTheWholeProjectMovesToTheUsersWorkspace() {
        val idea = projectDir.resolve(".idea").createDirectories()
        idea.resolve("elixir.xml").writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project version="4">
              <component name="ElixirToolManagerSettings">
                <option name="enabledManagers">
                  <set>
                    <option value="mise" />
                  </set>
                </option>
              </component>
            </project>
            """.trimIndent()
        )

        val project = PlatformTestUtil.loadAndOpenProject(projectDir, testRootDisposable)

        assertTrue(ToolManagerSettings.getInstance(project).isEnabled(MiseToolManager()))

        PlatformTestUtil.saveProject(project, true)

        val elixirXml = idea.resolve("elixir.xml")
        assertFalse(elixirXml.exists() && elixirXml.readText().contains("ElixirToolManagerSettings"))
        val workspaceXml = idea.resolve("workspace.xml").readText()
        assertTrue(workspaceXml, workspaceXml.contains("""<component name="ElixirToolManagerSettings">"""))
        assertTrue(workspaceXml, workspaceXml.contains("""<option value="mise" />"""))
    }
}
