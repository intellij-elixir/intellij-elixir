package org.elixir_lang.mix

import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.vfs.VirtualFile
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.mix.project.mixExs

/** Covers the module-name disambiguation an umbrella import depends on. */
class ProjectModuleNameForOtpAppsTest : PlatformTestCase() {

    /**
     * The umbrella from the reports: root and child both declare `app: :dup_name`, with a
     * uniquely-named sibling alongside them.
     */
    private fun umbrellaRoot(): VirtualFile {
        myFixture.addFileToProject("umbrella/mix.exs", mixExs("dup_name"))
        myFixture.addFileToProject("umbrella/apps/dup_name/mix.exs", mixExs("dup_name"))
        myFixture.addFileToProject("umbrella/apps/other_app/mix.exs", mixExs("other_app"))

        return myFixture.tempDirFixture.getFile("umbrella")!!
    }

    /** Module names keyed by each app's path relative to the umbrella root, so `/` is the root app. */
    private fun moduleNamesByRelativePath(): Map<String, String> {
        val root = umbrellaRoot()
        val otpApps = Project.findOtpApps(root, EmptyProgressIndicator())

        return Project
            .moduleNameForOtpApps(otpApps, root)
            .entries
            .associate { (otpApp, moduleName) ->
                otpApp.root.path.removePrefix(root.path).ifEmpty { "/" } to moduleName
            }
    }

    fun testRootAndChildSharingAnAppNameGetDistinctModuleNames() {
        val moduleNames = moduleNamesByRelativePath()

        assertEquals(
            "the app at the project root keeps its plain name, so an umbrella that already imported" +
                    " cleanly does not have its module renamed by this disambiguation",
            "dup_name",
            moduleNames["/"]
        )
        assertFalse(
            "the child app sharing the root's name must not be mapped to that same name",
            moduleNames["/apps/dup_name"] == moduleNames["/"]
        )
    }

    fun testKeywordDoProjectNamesItsApp() {
        myFixture.addFileToProject(
            "directory_name/mix.exs",
            """
            defmodule KeywordDo.MixProject do
              use Mix.Project

              def project, do: [app: :keyword_do, version: "0.1.0"]
            end
            """.trimIndent()
        )
        val root = myFixture.tempDirFixture.getFile("directory_name")!!
        val otpApps = Project.findOtpApps(root, EmptyProgressIndicator())

        assertEquals(listOf("keyword_do"), otpApps.map { it.name })
    }

    fun testUniquelyNamedAppKeepsItsOwnName() {
        assertEquals(
            "an app whose name nothing else claims is not disambiguated",
            "other_app",
            moduleNamesByRelativePath()["/apps/other_app"]
        )
    }
}
