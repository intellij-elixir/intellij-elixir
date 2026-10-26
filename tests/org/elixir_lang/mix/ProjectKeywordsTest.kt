package org.elixir_lang.mix

import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.impl.keywordValue

class ProjectKeywordsTest : PlatformTestCase() {
    fun testAppIsTheProjectsAppAtom() {
        val mixExs = mixExs(
            """
            defmodule MyApp.MixProject do
              use Mix.Project

              def project do
                [app: :"my_app", version: "0.1.0"]
              end
            end
            """
        )

        assertEquals("my_app", ProjectKeywords.app(mixExs))
    }

    fun testParenthesizedAppIsTheAtom() {
        val mixExs = mixExs(
            """
            defmodule MyApp.MixProject do
              use Mix.Project

              def project do
                [app: (:my_app), version: "0.1.0"]
              end
            end
            """
        )

        assertEquals("my_app", ProjectKeywords.app(mixExs))
    }

    fun testKeywordsAreTheBodysLastExpression() {
        val mixExs = mixExs(
            """
            defmodule MyApp.MixProject do
              use Mix.Project

              def project do
                [app: :discarded]
                [app: :returned, version: "0.1.0"]
              end
            end
            """
        )

        assertEquals(":returned", ProjectKeywords.keywords(mixExs)?.keywordValue("app")?.text)
        assertEquals("returned", ProjectKeywords.app(mixExs))
    }

    fun testKeywordDoProjectHasKeywords() {
        val mixExs = mixExs(
            """
            defmodule MyApp.MixProject do
              use Mix.Project

              def project, do: [app: :my_app, version: "0.1.0"]
            end
            """
        )

        assertEquals("\"0.1.0\"", ProjectKeywords.keywords(mixExs)?.keywordValue("version")?.text)
        assertEquals("my_app", ProjectKeywords.app(mixExs))
    }

    fun testNoProjectFunctionHasNoKeywords() {
        val mixExs = mixExs(
            """
            defmodule MyApp.MixProject do
              use Mix.Project

              defp project, do: [app: :private]

              def application do
                [extra_applications: [:logger]]
              end
            end
            """
        )

        assertNull(ProjectKeywords.keywords(mixExs))
        assertNull(ProjectKeywords.app(mixExs))
    }

    fun testConfigPathIsTheWrittenString() {
        val mixExs = mixExs(
            """
            defmodule MyApp.MixProject do
              use Mix.Project

              def project do
                [app: :my_app, config_path: "../../con\x66ig/config.exs"]
              end
            end
            """
        )

        assertEquals(ProjectKeywords.ConfigPath.Written("../../config/config.exs"), ProjectKeywords.configPath(mixExs))
    }

    fun testParenthesizedConfigPathIsTheString() {
        val mixExs = mixExs(
            """
            defmodule MyApp.MixProject do
              use Mix.Project

              def project do
                [app: :my_app, config_path: ("../../config/config.exs")]
              end
            end
            """
        )

        assertEquals(ProjectKeywords.ConfigPath.Written("../../config/config.exs"), ProjectKeywords.configPath(mixExs))
    }

    fun testNonLiteralConfigPathIsUnknown() {
        val mixExs = mixExs(
            """
            defmodule MyApp.MixProject do
              use Mix.Project

              def project do
                [app: :my_app, config_path: Path.join("config", "config.exs")]
              end
            end
            """
        )

        assertEquals(ProjectKeywords.ConfigPath.Unknown, ProjectKeywords.configPath(mixExs))
    }

    fun testAbsentConfigPathIsMixsDefault() {
        val mixExs = mixExs(
            """
            defmodule MyApp.MixProject do
              use Mix.Project

              def project, do: [app: :my_app]
            end
            """
        )

        assertEquals(ProjectKeywords.ConfigPath.Written("config/config.exs"), ProjectKeywords.configPath(mixExs))
    }

    fun testEmptyProjectListHasTheDefaultConfigPath() {
        val mixExs = mixExs(
            """
            defmodule MyApp.MixProject do
              use Mix.Project

              def project, do: []
            end
            """
        )

        assertEquals(ProjectKeywords.ConfigPath.Written("config/config.exs"), ProjectKeywords.configPath(mixExs))
    }

    fun testTupleProjectListHasAnUnknownConfigPath() {
        val mixExs = mixExs(
            """
            defmodule MyApp.MixProject do
              use Mix.Project

              def project, do: [{:config_path, "settings/main.exs"}]
            end
            """
        )

        assertEquals(ProjectKeywords.ConfigPath.Unknown, ProjectKeywords.configPath(mixExs))
    }

    private fun mixExs(text: String): ElixirFile =
        myFixture.configureByText(Project.MIX_EXS, text.trimIndent()) as ElixirFile
}
