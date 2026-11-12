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

        assertEquals(ProjectKeywords.Reading.Written("my_app"), ProjectKeywords.app(mixExs))
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

        assertEquals(ProjectKeywords.Reading.Written("my_app"), ProjectKeywords.app(mixExs))
    }

    /** A handler block may return another list, so a body without an `app:` is not known to have none. */
    fun testAProjectWithAHandlerBlockIsNotAbsentOfApp() {
        assertEquals(ProjectKeywords.Reading.Unknown, ProjectKeywords.app(handlerProject("[version: \"0.1.0\"]", "[app: :b]")))
    }

    /** What the body writes is read as before, handler or not. */
    fun testAProjectWithAHandlerBlockStillGivesItsBodysApp() {
        val mixExs = handlerProject("[app: :a]", "[app: :b]")

        assertEquals(ProjectKeywords.Reading.Written("a"), ProjectKeywords.app(mixExs))
        assertEquals(":a", ProjectKeywords.keywords(mixExs)?.keywordValue("app")?.text)
    }

    fun testAProjectWithAHandlerBlockStillGivesMixsDefaultConfigPath() {
        assertEquals(
            ProjectKeywords.Reading.Written("config/config.exs"),
            ProjectKeywords.configPath(handlerProject("[app: :a]", "[config_path: \"x.exs\"]")),
        )
    }

    /** `{:app, :a}` is a keyword element in tuple form, and `app` reads only the keyword tail. */
    fun testAnAppBesideATupleIsNotAbsent() {
        assertEquals(ProjectKeywords.Reading.Unknown, ProjectKeywords.app(keywordsProject("[{:app, :my_app}, deps: deps()]")))
    }

    fun testAnAppWrittenAfterATupleIsStillRead() {
        assertEquals(
            ProjectKeywords.Reading.Written("my_app"),
            ProjectKeywords.app(keywordsProject("[{:version, \"0.1.0\"}, app: :my_app]")),
        )
    }

    fun testAConfigPathBesideATupleIsMixsDefault() {
        assertEquals(
            ProjectKeywords.Reading.Written("config/config.exs"),
            ProjectKeywords.configPath(keywordsProject("[{:app, :my_app}, deps: deps()]")),
        )
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
        assertEquals(ProjectKeywords.Reading.Written("returned"), ProjectKeywords.app(mixExs))
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
        assertEquals(ProjectKeywords.Reading.Written("my_app"), ProjectKeywords.app(mixExs))
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
        assertEquals(ProjectKeywords.Reading.Unknown, ProjectKeywords.app(mixExs))
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

        assertEquals(ProjectKeywords.Reading.Written("../../config/config.exs"), ProjectKeywords.configPath(mixExs))
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

        assertEquals(ProjectKeywords.Reading.Written("../../config/config.exs"), ProjectKeywords.configPath(mixExs))
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

        assertEquals(ProjectKeywords.Reading.Unknown, ProjectKeywords.configPath(mixExs))
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

        assertEquals(ProjectKeywords.Reading.Written("config/config.exs"), ProjectKeywords.configPath(mixExs))
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

        assertEquals(ProjectKeywords.Reading.Written("config/config.exs"), ProjectKeywords.configPath(mixExs))
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

        assertEquals(ProjectKeywords.Reading.Unknown, ProjectKeywords.configPath(mixExs))
    }

    fun testAppIsAbsentWhereTheListHasNone() {
        val mixExs = mixExs(
            """
            defmodule MyApp.MixProject do
              use Mix.Project

              def project, do: [apps_path: "apps", deps: deps()]

              defp deps, do: []
            end
            """
        )

        assertEquals(ProjectKeywords.Reading.Absent, ProjectKeywords.app(mixExs))
    }

    fun testAppIsAbsentFromAnEmptyProjectList() {
        val mixExs = mixExs(
            """
            defmodule MyApp.MixProject do
              use Mix.Project

              def project, do: []
            end
            """
        )

        assertEquals(ProjectKeywords.Reading.Absent, ProjectKeywords.app(mixExs))
    }

    fun testAppNamedThroughAnAttributeIsUnknown() {
        val mixExs = mixExs(
            """
            defmodule MyApp.MixProject do
              use Mix.Project

              @app :my_app

              def project, do: [app: @app, version: "0.1.0"]
            end
            """
        )

        assertEquals(ProjectKeywords.Reading.Unknown, ProjectKeywords.app(mixExs))
    }

    fun testAppOfAProjectListThatIsNotKeywordsIsUnknown() {
        val mixExs = mixExs(
            """
            defmodule MyApp.MixProject do
              use Mix.Project

              def project, do: [{:app, :my_app}]
            end
            """
        )

        assertEquals(ProjectKeywords.Reading.Unknown, ProjectKeywords.app(mixExs))
    }

    /** A `project/0` that returns [body], or [handled] where its `rescue` block runs. */
    private fun handlerProject(body: String, handled: String): ElixirFile =
        mixExs("defmodule MyApp.MixProject do\n  use Mix.Project\n\n  def project do\n    $body\n  rescue\n    _ -> $handled\n  end\nend\n")

    /** A `project/0` that returns [body]. */
    private fun keywordsProject(body: String): ElixirFile =
        mixExs("defmodule MyApp.MixProject do\n  use Mix.Project\n\n  def project, do: $body\nend\n")

    private fun mixExs(text: String): ElixirFile =
        myFixture.configureByText(Project.MIX_EXS, text.trimIndent()) as ElixirFile
}
