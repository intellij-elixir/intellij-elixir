package org.elixir_lang.mix

import com.intellij.psi.PsiElement
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.mix.ProjectKeywords.UsesMixProject
import org.elixir_lang.psi.ElixirFile

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

    /** A `rescue` block may return another list, so a body without an `app:` is one of two readings. */
    fun testAnAppOnlyTheRescueGivesIsOneOfTwo() {
        assertEquals(
            ambiguous(ProjectKeywords.Reading.Absent, ProjectKeywords.Reading.Written("b")),
            ProjectKeywords.app(handlerProject("[version: \"0.1.0\"]", "[app: :b]")),
        )
    }

    fun testABodyAndARescueThatGiveTwoAppsAreBothRead() {
        assertEquals(
            ambiguous(ProjectKeywords.Reading.Written("a"), ProjectKeywords.Reading.Written("b")),
            ProjectKeywords.app(handlerProject("[app: :a]", "[app: :b]")),
        )
    }

    fun testABodyAndARescueThatGiveOneAppAreOneReading() {
        assertEquals(ProjectKeywords.Reading.Written("a"), ProjectKeywords.app(handlerProject("[app: :a]", "[app: :a]")))
    }

    fun testARescueThatGivesAConfigPathIsOneOfTwoWithMixsDefault() {
        assertEquals(
            ambiguous(ProjectKeywords.Reading.Written("config/config.exs"), ProjectKeywords.Reading.Written("x.exs")),
            ProjectKeywords.configPath(handlerProject("[app: :a]", "[config_path: \"x.exs\"]")),
        )
    }

    fun testABodyAndARescueThatGiveTwoConfigPathsAreBothRead() {
        assertEquals(
            ambiguous(ProjectKeywords.Reading.Written("a.exs"), ProjectKeywords.Reading.Written("b.exs")),
            ProjectKeywords.configPath(handlerProject("[config_path: \"a.exs\"]", "[config_path: \"b.exs\"]")),
        )
    }

    /** `else` clauses match the body's value, so the function returns what they return, never the body's. */
    fun testAnElseReplacesTheBodysList() {
        assertEquals(ProjectKeywords.Reading.Written("b"), ProjectKeywords.app(blockProject("[app: :a]", "else\n    _ -> [app: :b]")))
    }

    fun testEachElseClauseIsAReading() {
        assertEquals(
            ambiguous(ProjectKeywords.Reading.Written("b"), ProjectKeywords.Reading.Written("c")),
            ProjectKeywords.app(blockProject("[app: :a]", "else\n    [] -> [app: :b]\n    _ -> [app: :c]")),
        )
    }

    fun testACatchThatReturnsNoListIsAnUnknownReading() {
        assertEquals(
            ambiguous(ProjectKeywords.Reading.Written("a"), ProjectKeywords.Reading.Unknown),
            ProjectKeywords.app(blockProject("[app: :a]", "catch\n    _ -> foo()")),
        )
    }

    /** `after` does not change the value a function returns. */
    fun testAnAfterKeepsTheBodysList() {
        assertEquals(ProjectKeywords.Reading.Written("a"), ProjectKeywords.app(blockProject("[app: :a]", "after\n    :ok")))
    }

    fun testARescueKeywordIsReadAsTheBlockIs() {
        assertEquals(
            ambiguous(ProjectKeywords.Reading.Written("a"), ProjectKeywords.Reading.Written("b")),
            ProjectKeywords.app(keywordsProject("[app: :a], rescue: (_ -> [app: :b])")),
        )
    }

    fun testAnElseKeywordReplacesTheBodysList() {
        assertEquals(ProjectKeywords.Reading.Written("b"), ProjectKeywords.app(keywordsProject("[app: :a], else: (_ -> [app: :b])")))
    }

    /** `Keyword.get/2` reads a list's pairs in order, tuples included, so `{:app, :a}` is an `app:`. */
    fun testAnAppWrittenAsATupleIsRead() {
        assertEquals(ProjectKeywords.Reading.Written("my_app"), ProjectKeywords.app(keywordsProject("[{:app, :my_app}, deps: deps()]")))
    }

    fun testAnAppWrittenAsATupleBeforeAKeywordIsTheFirst() {
        assertEquals(ProjectKeywords.Reading.Written("actual"), ProjectKeywords.app(keywordsProject("[{:app, :actual}, app: :other]")))
    }

    /** `Keyword.merge/2` raises on an element that is no pair, so Mix reads no project from such a list. */
    fun testAnElementThatIsNoPairMakesTheAppUnknown() {
        assertEquals(ProjectKeywords.Reading.Unknown, ProjectKeywords.app(keywordsProject("[foo, app: :a]")))
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

        assertEquals(ProjectKeywords.Reading.Written("returned"), ProjectKeywords.app(mixExs))
    }

    fun testKeywordDoProjectHasItsApp() {
        val mixExs = mixExs(
            """
            defmodule MyApp.MixProject do
              use Mix.Project

              def project, do: [app: :my_app, version: "0.1.0"]
            end
            """
        )

        assertEquals(ProjectKeywords.Reading.Written("my_app"), ProjectKeywords.app(mixExs))
    }

    fun testNoProjectFunctionHasAnUnknownApp() {
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

    /** `Config.Reader` raises on a charlist `config_path:`, so it names no file. */
    fun testACharlistConfigPathIsUnknown() {
        assertEquals(ProjectKeywords.Reading.Unknown, ProjectKeywords.configPath(keywordsProject("[app: :my_app, config_path: 'config/other.exs']")))
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

    fun testAConfigPathWrittenAsATupleIsRead() {
        val mixExs = mixExs(
            """
            defmodule MyApp.MixProject do
              use Mix.Project

              def project, do: [{:config_path, "settings/main.exs"}]
            end
            """
        )

        assertEquals(ProjectKeywords.Reading.Written("settings/main.exs"), ProjectKeywords.configPath(mixExs))
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

    fun testAnAppAloneWrittenAsATupleIsRead() {
        val mixExs = mixExs(
            """
            defmodule MyApp.MixProject do
              use Mix.Project

              def project, do: [{:app, :my_app}]
            end
            """
        )

        assertEquals(ProjectKeywords.Reading.Written("my_app"), ProjectKeywords.app(mixExs))
    }

    /** Mix keeps a key the project gives twice, and reads the first. */
    fun testAnAppGivenTwiceIsTheFirst() {
        assertEquals(ProjectKeywords.Reading.Written("a"), ProjectKeywords.app(keywordsProject("[app: :a, app: :b]")))
    }

    fun testAConfigPathGivenTwiceIsTheFirst() {
        assertEquals(
            ProjectKeywords.Reading.Written("a.exs"),
            ProjectKeywords.configPath(keywordsProject("[app: :a, config_path: \"a.exs\", config_path: \"b.exs\"]")),
        )
    }

    /** `project/0` takes no argument, so its first clause is the one Mix calls, whatever it returns. */
    fun testAppOfAFirstProjectClauseThatIsNotAListIsUnknown() {
        val mixExs = mixExs(
            """
            defmodule MyApp.MixProject do
              use Mix.Project

              def project, do: config()

              def project, do: [app: :my_app]

              defp config, do: [app: :other]
            end
            """
        )

        assertEquals(ProjectKeywords.Reading.Unknown, ProjectKeywords.app(mixExs))
    }

    /** Mix reads the project of the module that `use Mix.Project` pushes, not a helper module's `project/0` before it. */
    fun testAHelpersProjectBeforeTheMixProjectIsPassedOver() {
        val mixExs = mixExs(
            """
            defmodule Helper do
              def project, do: []
            end

            defmodule MyApp.MixProject do
              use Mix.Project

              def project, do: [app: :my_app, config_path: "a.exs"]
            end
            """
        )

        assertEquals(ProjectKeywords.Reading.Written("my_app"), ProjectKeywords.app(mixExs))
        assertEquals(ProjectKeywords.Reading.Written("a.exs"), ProjectKeywords.configPath(mixExs))
    }

    /** `use Elixir.Mix.Project` names the module `use Mix.Project` does. */
    fun testAnElixirHeadedMixProjectIsTheProject() {
        val mixExs = mixExs(
            """
            defmodule Helper do
              def project, do: []
            end

            defmodule MyApp.MixProject do
              use Elixir.Mix.Project

              def project, do: [app: :my_app]
            end
            """
        )

        assertEquals(ProjectKeywords.Reading.Written("my_app"), ProjectKeywords.app(mixExs))
    }

    fun testAMixProjectUsedThroughAnAliasIsTheProject() {
        val mixExs = mixExs(
            """
            defmodule MyApp.MixProject do
              alias Mix.Project
              use Project

              def project, do: [app: :my_app]
            end
            """
        )

        assertEquals(ProjectKeywords.Reading.Written("my_app"), ProjectKeywords.app(mixExs))
    }

    fun testAMixProjectUsedThroughAnAliasBesideAHelpersProjectIsTheProject() {
        val mixExs = mixExs(
            """
            defmodule Helper do
              def project, do: [app: :helper]
            end

            defmodule MyApp.MixProject do
              alias Mix.Project
              use Project

              def project, do: [app: :my_app]
            end
            """
        )

        assertEquals(ProjectKeywords.Reading.Written("my_app"), ProjectKeywords.app(mixExs))
    }

    fun testAMixProjectUsedThroughAnAliasWithAsIsTheProject() {
        val mixExs = mixExs(
            """
            defmodule Helper do
              def project, do: [app: :helper]
            end

            defmodule MyApp.MixProject do
              alias Mix.Project, as: MixProject
              use MixProject

              def project, do: [app: :my_app]
            end
            """
        )

        assertEquals(ProjectKeywords.Reading.Written("my_app"), ProjectKeywords.app(mixExs))
    }

    /** `alias Elixir.Mix.Project` aliases the module `alias Mix.Project` does. */
    fun testAMixProjectUsedThroughAnElixirHeadedAliasIsTheProject() {
        val mixExs = mixExs(
            """
            defmodule Helper do
              def project, do: [app: :helper]
            end

            defmodule MyApp.MixProject do
              alias Elixir.Mix.Project, as: MixProject
              use MixProject

              def project, do: [app: :my_app]
            end
            """
        )

        assertEquals(ProjectKeywords.Reading.Written("my_app"), ProjectKeywords.app(mixExs))
    }

    /** `use` of another module's `Project` through an alias pushes no project, so Mix reads none from the file. */
    fun testAnAliasOfAnotherModulesProjectIsNotTheProject() {
        val mixExs = mixExs(
            """
            defmodule MyApp.MixProject do
              alias MyApp.Project
              use Project

              def project, do: [app: :my_app]
            end
            """
        )

        assertEquals(ProjectKeywords.Reading.Unknown, ProjectKeywords.app(mixExs))
    }

    /** A module that doesn't `use Mix.Project` pushes no project, so Mix reads none from the file. */
    fun testAProjectOfAModuleThatDoesNotUseMixProjectIsUnknown() {
        val mixExs = mixExs(
            """
            defmodule MyApp.MixProject do
              def project, do: [app: :my_app]
            end
            """
        )

        assertEquals(ProjectKeywords.Reading.Unknown, ProjectKeywords.app(mixExs))
    }

    // Each spelling names `Mix.Project` as Elixir expands it.

    fun testAMixProjectUsedThroughAChainedAliasIsTheProject() {
        assertApp("alias Mix, as: M\nalias M.Project\nuse Project")
    }

    fun testAMixProjectUsedThroughAnAliasBeforeTheModuleIsTheProject() {
        assertEquals(
            ProjectKeywords.Reading.Written("my_app"),
            ProjectKeywords.app(mixExs("alias Mix.Project\n\n" + projectModule("use Project"))),
        )
    }

    fun testAMixProjectUsedThroughAnElixirHeadedAliasWithoutAsIsTheProject() {
        assertApp("alias Elixir.Mix.Project\nuse Project")
    }

    fun testAMixProjectUsedThroughAMultiAliasIsTheProject() {
        assertApp("alias Mix.{Project}\nuse Project")
    }

    // An alias applies only after it, in the scope it is written in.

    fun testAnAliasInsideAFunctionIsNotInTheModulesScope() {
        assertNotTheProject("def project do\n  alias Mix.Project\n  [app: :my_app]\nend\n\nuse Project", project = "")
    }

    fun testAnAliasInsideAnIfIsNotInTheModulesScope() {
        assertNotTheProject("if true do\n  alias Mix.Project\nend\n\nuse Project")
    }

    fun testAnAliasAfterTheUseIsNotInItsScope() {
        assertNotTheProject("use Project\nalias Mix.Project")
    }

    /** A nested `defmodule` aliases its first segment to the module it defines, so `Mix.Project` names `Outer.Mix.Project`. */
    fun testANestedMixModuleMakesMixProjectAnotherModule() {
        assertNotTheProject("defmodule Mix.Project do\nend\n\nuse Mix.Project")
    }

    /** `defprotocol` defines its module through `defmodule`, so it aliases its first segment as a nested `defmodule` does. */
    fun testANestedMixProtocolMakesMixProjectAnotherModule() {
        assertNotTheProject("defprotocol Mix.Project do\n  def f(x)\nend\n\nuse Mix.Project")
    }

    fun testAProjectModuleNestedInAnotherModuleIsTheProject() {
        val mixExs = mixExs(
            """
            defmodule Outer do
              defmodule Inner do
                use Mix.Project

                def project, do: [app: :inner]
              end
            end
            """
        )

        assertEquals(ProjectKeywords.Reading.Written("inner"), ProjectKeywords.app(mixExs))
    }

    /** `Mix.Project` reads the project of the first module that uses it, and raises where that module has no `project/0`. */
    fun testAFirstMixProjectWithoutAProjectFunctionIsUnknown() {
        val mixExs = mixExs(
            """
            defmodule First do
              use Mix.Project
            end

            defmodule Second do
              use Mix.Project

              def project, do: [app: :second]
            end
            """
        )

        assertEquals(ProjectKeywords.Reading.Unknown, ProjectKeywords.app(mixExs))
    }

    fun testElixircOptionsAfterATupleAreRead() {
        assertEquals(
            "[token_metadata: true]",
            written(parserOptions(keywordsProject("[{:app, :a}, elixirc_options: [parser_options: [token_metadata: true]]]"))),
        )
    }

    fun testAnElixircOptionWrittenAsATupleIsRead() {
        assertEquals(
            "[columns: true]",
            written(parserOptions(keywordsProject("[elixirc_options: [{:parser_options, [columns: true]}]]"))),
        )
    }

    fun testAnElixircOptionAfterOneWrittenAsATupleIsTheLast() {
        assertEquals(
            "[columns: true]",
            written(parserOptions(keywordsProject("[elixirc_options: [{:parser_options, x}, parser_options: [columns: true]]]"))),
        )
    }

    /** A `rescue` block may return other `elixirc_options:`, so the body's are one of two readings. */
    fun testARescueThatGivesNoElixircOptionsIsOneOfTwo() {
        val reading = parserOptions(handlerProject("[app: :a, elixirc_options: [parser_options: []]]", "[app: :a]"))

        assertEquals(
            listOf("Written([])", "Absent"),
            reading.candidates.map { if (it is ProjectKeywords.Reading.Written) "Written(${it.value.text})" else it.toString() },
        )
    }

    fun testABodyAndARescueWithoutElixircOptionsAreAbsentOfOne() {
        assertEquals(
            ProjectKeywords.Reading.Absent,
            parserOptions(handlerProject("[app: :a]", "[app: :a]")),
        )
    }

    /** `Code.compiler_options/1` puts each pair in turn, so an option the project gives twice is the last. */
    fun testAnElixircOptionGivenTwiceIsTheLast() {
        val reading = parserOptions(
            keywordsProject("[app: :a, elixirc_options: [parser_options: [columns: false], parser_options: [columns: true]]]"),
        )

        assertEquals("[columns: true]", written(reading))
    }

    /** The `parser_options` of [mixExs]'s `elixirc_options:`, each as written. */
    private fun parserOptions(mixExs: ElixirFile): ProjectKeywords.Reading<PsiElement> =
        ProjectKeywords.elixircOption(mixExs, "parser_options") { ProjectKeywords.Reading.Written(it) }

    private fun <T> ambiguous(vararg candidates: ProjectKeywords.Reading.Single<T>): ProjectKeywords.Reading<T> =
        ProjectKeywords.Reading.Ambiguous(candidates.toList())

    private fun written(reading: ProjectKeywords.Reading<PsiElement>): String? = (reading as? ProjectKeywords.Reading.Written)?.value?.text

    /** A `project/0` whose `do` block returns [body], followed by [blocks]. */
    private fun blockProject(body: String, blocks: String): ElixirFile =
        mixExs("defmodule MyApp.MixProject do\n  use Mix.Project\n\n  def project do\n    $body\n  $blocks\n  end\nend\n")

    /** A `project/0` that returns [body], or [handled] where its `rescue` block runs. */
    private fun handlerProject(body: String, handled: String): ElixirFile = blockProject(body, "rescue\n    _ -> $handled")

    /** A `project/0` that returns [body]. */
    private fun keywordsProject(body: String): ElixirFile =
        mixExs("defmodule MyApp.MixProject do\n  use Mix.Project\n\n  def project, do: $body\nend\n")

    /** That a module whose [body] precedes its `project/0` is read as the project of `my_app`. */
    private fun assertApp(body: String) {
        assertEquals(ProjectKeywords.Reading.Written("my_app"), ProjectKeywords.app(mixExs(projectModule(body))))
    }

    /** That a module whose [body] precedes [project] is no project, as its `use` names another module than `Mix.Project`. */
    private fun assertNotTheProject(body: String, project: String = MY_APP_PROJECT) {
        val mixExs = mixExs(projectModule(body, project))

        assertEquals(ProjectKeywords.Reading.Unknown, ProjectKeywords.app(mixExs))
        assertEquals(UsesMixProject.NO, ProjectKeywords.projectModules(mixExs).first().usesMixProject)
    }

    /** A module of [body], then [project]. */
    private fun projectModule(body: String, project: String = MY_APP_PROJECT): String =
        "defmodule MyApp.MixProject do\n${body.prependIndent("  ")}\n\n${project.prependIndent("  ")}\nend\n"

    private fun mixExs(text: String): ElixirFile =
        myFixture.configureByText(Project.MIX_EXS, text.trimIndent()) as ElixirFile
}

/** The `project/0` of a module read as the project of `my_app`. */
private const val MY_APP_PROJECT = "def project, do: [app: :my_app]"
