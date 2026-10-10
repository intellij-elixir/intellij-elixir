package org.elixir_lang.mix

import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.vfs.VirtualFile
import org.elixir_lang.PlatformTestCase

/**
 * A directory whose name starts with `.` holds tooling state, such as `.claude/worktrees/<name>/` checkouts of
 * the same project, so a `mix.exs` below one is not an app of the project. Counting it would make a project
 * with one app read as an umbrella.
 */
class ProjectFindOtpAppsHiddenDirectoryTest : PlatformTestCase() {
    fun testMixExsBelowAHiddenDirectoryIsNotAnApp() {
        addMixExs("proj", "proj")
        addMixExs("proj/.claude/worktrees/a", "proj")
        addMixExs("proj/.claude/worktrees/b", "proj")

        assertEquals(listOf("/"), appPaths("proj"))
    }

    fun testAnyHiddenDirectoryIsSkipped() {
        addMixExs("proj", "proj")
        addMixExs("proj/.anything/nested", "nested")

        assertEquals(listOf("/"), appPaths("proj"))
    }

    fun testAnUmbrellasChildAppsAreStillFound() {
        addMixExs("umbrella", "umbrella")
        addMixExs("umbrella/apps/a", "a")
        addMixExs("umbrella/apps/b", "b")

        assertEquals(listOf("/", "/apps/a", "/apps/b"), appPaths("umbrella"))
    }

    fun testAProjectRootThatIsItselfHiddenStillFindsItsApp() {
        addMixExs(".hidden_proj", "hidden_proj")

        assertEquals(listOf("/"), appPaths(".hidden_proj"))
    }

    private fun addMixExs(directory: String, app: String) {
        myFixture.addFileToProject(
            "$directory/mix.exs",
            """
            defmodule ${app.replaceFirstChar { it.uppercase() }}.MixProject do
              use Mix.Project

              def project do
                [app: :$app, version: "0.1.0"]
              end
            end
            """.trimIndent()
        )
    }

    /** Each app's path relative to [rootDirectory], so `/` is the app at the root. */
    private fun appPaths(rootDirectory: String): List<String> {
        val root: VirtualFile = myFixture.tempDirFixture.getFile(rootDirectory)!!

        return Project.findOtpApps(root, EmptyProgressIndicator()).map { it.root.path.removePrefix(root.path).ifEmpty { "/" } }.sorted()
    }
}
