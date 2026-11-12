package org.elixir_lang.mix

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.AppExecutorUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.ElixirAccessExpression
import org.elixir_lang.psi.ElixirDoBlock
import org.elixir_lang.psi.ElixirFile
import java.util.concurrent.TimeUnit

class DepsTest : PlatformTestCase() {
    /**
     * No source parses to an access expression without exactly one child, so the test gives a second child to the one ending
     * a `deps` helper.
     */
    fun testDepsHelperEndingInAccessExpressionWithoutExactlyOneChildHasNoDeps() {
        val psiFile = myFixture.configureByText(
            "mix.exs",
            """
            defmodule Sample.MixProject do
              def project do
                [deps: deps()]
              end

              defp deps do
                [ecto_dep()]
              end

              defp ecto_dep do
                1
              end
            end
            """.trimIndent()
        )
        val accessExpression = PsiTreeUtil.findChildrenOfType(psiFile, ElixirAccessExpression::class.java).single {
            PsiTreeUtil.getParentOfType(it, ElixirDoBlock::class.java)?.parent?.text?.startsWith("defp ecto_dep") == true
        }

        WriteCommandAction.runWriteCommandAction(project) {
            accessExpression.node.addChild(accessExpression.firstChild.copy().node)
        }

        assertEquals(2, accessExpression.children.size)

        val gatherer = DepGatherer()
        val indicator = EmptyProgressIndicator()
        val cancellation = AppExecutorUtil.getAppScheduledExecutorService().schedule(indicator::cancel, 10, TimeUnit.SECONDS)

        try {
            ProgressManager.getInstance().runProcess({ psiFile.accept(gatherer) }, indicator)
        } finally {
            cancellation.cancel(false)
        }

        assertFalse("gathering deps did not return within 10 seconds", indicator.isCanceled)
        assertEmpty(gatherer.depSet)
    }

    /** A `deps` function written `defp deps, do: [...]` returns its list as a `do:` value, as one with a `do` block does. */
    fun testADepsFunctionWrittenWithADoKeywordHasItsDeps() {
        val gatherer = gathered(
            """
            defmodule Sample.MixProject do
              def project do
                [deps: deps()]
              end

              defp deps, do: [{:foo, "~> 1.0"}]
            end
            """.trimIndent()
        )

        assertEquals(setOf("foo"), gatherer.depSet.map { it.application }.toSet())
    }

    /** A `project/0` written `def project, do: [...]` returns its list as a `do:` value, as one with a `do` block does. */
    fun testAProjectFunctionWrittenWithADoKeywordHasItsDepsAndDepsPath() {
        val gatherer = gathered(
            """
            defmodule Sample.MixProject do
              def project, do: [deps_path: "../../deps", deps: deps()]

              defp deps do
                [{:foo, "~> 1.0"}]
              end
            end
            """.trimIndent()
        )

        assertEquals(setOf("foo"), gatherer.depSet.map { it.application }.toSet())
        assertEquals("../../deps", gatherer.depsPath)
    }

    fun testAProjectFunctionReturningAnEmptyListHasNoDeps() {
        listOf("def project, do: []", "def project do\n  []\nend").forEach { project ->
            val gatherer = gathered("defmodule Sample.MixProject do\n${project.prependIndent("  ")}\nend\n")

            assertEmpty(project, gatherer.depSet)
            assertNull(project, gatherer.depsPath)
        }
    }

    /** A dependency's app is its tuple's first atom, whatever the requirement or options say, and nothing is resolved. */
    fun testDeclaredNamesAreEachTuplesFirstAtom() {
        assertDeclared(
            setOf("phoenix", "foo"),
            """
            def project, do: [app: :a, deps: deps()]

            defp deps, do: [{:phoenix, "~> 1.7"}, {:foo, path: "../bar"}]
            """,
        )
    }

    fun testDeclaredNamesOfAGeneratedPhoenixProject() {
        assertDeclared(
            setOf("phoenix", "phoenix_live_view", "phoenix_live_reload", "esbuild", "heroicons", "bandit"),
            """
            def project, do: [app: :a, deps: deps()]

            defp deps do
              [
                {:phoenix, "~> 1.7.14"},
                {:phoenix_live_view, "~> 1.0", override: true},
                {:phoenix_live_reload, "~> 1.2", only: :dev},
                {:esbuild, "~> 0.8", runtime: Mix.env() == :dev},
                {:heroicons, github: "tailwindlabs/heroicons", tag: "v2.1.1", sparse: "optimized", app: false, compile: false, depth: 1},
                {:bandit, "~> 1.5"}
              ]
            end
            """,
        )
    }

    fun testNoDepsKeywordDeclaresNone() {
        assertDeclared(emptySet(), "def project, do: [app: :a]")
    }

    fun testAnEmptyProjectListDeclaresNone() {
        assertDeclared(emptySet(), "def project, do: []")
    }

    fun testADepsHelperWithAnEmptyListDeclaresNone() {
        assertDeclared(emptySet(), "def project, do: [deps: deps()]\n\ndefp deps, do: []")
    }

    fun testAProjectListThatIsNotKeywordsIsUnread() {
        assertUnread("def project, do: [{:config_path, \"settings/main.exs\"}]")
    }

    fun testNoProjectFunctionIsUnread() {
        assertUnread("defp project, do: [deps: deps()]\n\ndefp deps, do: [{:foo, \"~> 1.0\"}]")
    }

    fun testAnElementThatIsACallIsUnread() {
        assertUnread("def project, do: [deps: deps()]\n\ndefp deps, do: [{:foo, \"~> 1.0\"}, ecto_dep()]")
    }

    fun testATupleWhoseFirstElementIsNotAnAtomIsUnread() {
        assertUnread("def project, do: [deps: deps()]\n\ndefp deps, do: [{name(), \"~> 1.0\"}]")
    }

    fun testDepsJoinedByAnOperatorAreUnread() {
        assertUnread("def project, do: [deps: deps() ++ extra()]\n\ndefp deps, do: [{:foo, \"~> 1.0\"}]")
    }

    fun testAnInlineDepsListIsUnread() {
        assertUnread("def project, do: [deps: [{:foo, \"~> 1.0\"}]]")
    }

    fun testDepsNamedThroughAnAttributeAreUnread() {
        assertUnread("@deps [{:foo, \"~> 1.0\"}]\n\ndef project, do: [deps: @deps]")
    }

    fun testADepsCallWithNoDefinerInTheFileIsUnread() {
        assertUnread("def project, do: [deps: deps()]")
    }

    /** Only the module whose `project/0` names `deps/0` defines it, so another module's function of that name is not read. */
    fun testADepsFunctionOfAnotherModuleIsNotTheProjects() {
        assertEquals(
            ProjectKeywords.Reading.Written(emptySet<String>()),
            declaredIn(
                """
                defmodule Sample.MixProject do
                  def project, do: [deps: deps()]

                  defp deps, do: []
                end

                defmodule Sample.Other do
                  defp deps, do: [{:ghost, "~> 1.0"}]
                end
                """.trimIndent()
            ),
        )
    }

    /** A bare `deps` is the variable `project/0` bound earlier, not a call of `deps/0`, and its value is never read. */
    fun testABareDepsNameBoundByAMatchIsUnread() = assertUnread(bound("deps = [{:foo, \"~> 1.0\"}]"))

    fun testABareDepsNameBoundInATuplePatternIsUnread() = assertUnread(bound("{deps, _} = {[{:foo, \"~> 1.0\"}], 1}"))

    fun testABareDepsNameBoundInAChainOfMatchesIsUnread() = assertUnread(bound("_ = other = deps = [{:foo, \"~> 1.0\"}]"))

    fun testTheWatcherGathersNothingForABareDepsNameBoundByAMatch() =
        assertEmpty(gatheredApps(bound("deps = [{:foo, \"~> 1.0\"}]")))

    fun testTheWatcherGathersNothingForABareDepsNameBoundInATuplePattern() =
        assertEmpty(gatheredApps(bound("{deps, _} = {[{:foo, \"~> 1.0\"}], 1}")))

    fun testTheWatcherGathersNothingForABareDepsNameBoundInAChainOfMatches() =
        assertEmpty(gatheredApps(bound("_ = other = deps = [{:foo, \"~> 1.0\"}]")))

    fun testABareDepsNameThatProjectDoesNotBindIsItsDepsFunction() {
        val body = "def project do\n  other = 1\n  [deps: deps]\nend\n\ndefp deps, do: [{:ghost, \"~> 1.0\"}]"

        assertEquals(ProjectKeywords.Reading.Written(setOf("ghost")), declared(body))
        assertEquals(setOf("ghost"), gatheredApps(body))
    }

    fun testADepsCallWithParenthesesIsItsDepsFunctionWhateverProjectBinds() {
        val body = "def project do\n  deps = [{:foo, \"~> 1.0\"}]\n  [deps: deps()]\nend\n\ndefp deps, do: [{:ghost, \"~> 1.0\"}]"

        assertEquals(ProjectKeywords.Reading.Written(setOf("ghost")), declared(body))
        assertEquals(setOf("ghost"), gatheredApps(body))
    }

    /** A match binds only in the clause it is in: not inside an `fn`, another body, or a `with` clause. */
    fun testABareDepsNameAssignedInsideAnFnIsItsDepsFunction() =
        assertDeclared(setOf("ghost"), bound("fun = fn -> deps = [{:foo, \"~> 1.0\"}] end"))

    fun testABareDepsNameAssignedInACaseArmIsItsDepsFunction() =
        assertDeclared(setOf("ghost"), bound("case 1 do\n  1 -> deps = [{:foo, \"~> 1.0\"}]\nend"))

    fun testABareDepsNameAssignedInAKeywordBodyIsItsDepsFunction() =
        assertDeclared(setOf("ghost"), bound("if true, do: (deps = [{:foo, \"~> 1.0\"}])"))

    fun testABareDepsNameAssignedInAWithClauseIsItsDepsFunction() =
        assertDeclared(setOf("ghost"), bound("with {:ok, one} <- fetch(), deps = [one] do\n  :ok\nend"))

    fun testTheWatcherGathersTheDepsFunctionForABareDepsNameAssignedInsideAnFn() =
        assertEquals(setOf("ghost"), gatheredApps(bound("fun = fn -> deps = [{:foo, \"~> 1.0\"}] end")))

    fun testTheWatcherGathersTheDepsFunctionForABareDepsNameAssignedInACaseArm() =
        assertEquals(setOf("ghost"), gatheredApps(bound("case 1 do\n  1 -> deps = [{:foo, \"~> 1.0\"}]\nend")))

    fun testTheWatcherGathersTheDepsFunctionForABareDepsNameAssignedInAKeywordBody() =
        assertEquals(setOf("ghost"), gatheredApps(bound("if true, do: (deps = [{:foo, \"~> 1.0\"}])")))

    fun testTheWatcherGathersTheDepsFunctionForABareDepsNameAssignedInAWithClause() =
        assertEquals(setOf("ghost"), gatheredApps(bound("with {:ok, one} <- fetch(), deps = [one] do\n  :ok\nend")))

    /** An `else:` with no `do:` beside it is an option of the call, not a body, so its match binds in the clause. */
    fun testABareDepsNameAssignedInAnElseOptionWithoutDoIsUnread() =
        assertUnread(bound("foo(else: deps = [{:foo, \"~> 1.0\"}])"))

    fun testTheWatcherGathersNothingForABareDepsNameAssignedInAnElseOptionWithoutDo() =
        assertEmpty(gatheredApps(bound("foo(else: deps = [{:foo, \"~> 1.0\"}])")))

    /** A match in the arguments of a call that opens a body binds in the clause. */
    fun testABareDepsNameAssignedInACaseSubjectIsUnread() =
        assertUnread(bound("case deps = [{:foo, \"~> 1.0\"}] do\n  _ -> :ok\nend"))

    fun testTheWatcherGathersNothingForABareDepsNameAssignedInACaseSubject() =
        assertEmpty(gatheredApps(bound("case deps = [{:foo, \"~> 1.0\"}] do\n  _ -> :ok\nend")))

    /** `{:app, :a}` is a keyword element in tuple form, and the index reads none, so it can't say `deps:` is missing. */
    fun testAProjectListWithATupleBeforeItsKeywordsHasUnknownDeps() =
        assertUnread("def project, do: [{:app, :a}, deps: deps()]\n\ndefp deps, do: []")

    fun testAProjectListWithATupleBeforeItsKeywordsHasUnknownDepsWhereItNamesNone() =
        assertUnread("def project, do: [{:app, :a}, version: \"0.1.0\"]")

    fun testAProjectListOfKeywordsAloneHasNoDepsWhereItNamesNone() =
        assertDeclared(emptySet(), "def project, do: [app: :a, version: \"0.1.0\"]")

    /**
     * A function with a `rescue`, `catch` or `else` block may return another value than its body's, so the body's list
     * isn't its whole answer, and what the index reads from it is unknown.
     */
    fun testADepsFunctionWithARescueBlockIsUnread() = assertUnread(depsBlock("rescue"))

    fun testADepsFunctionWithACatchBlockIsUnread() = assertUnread(depsBlock("catch"))

    fun testADepsFunctionWithAnElseBlockIsUnread() = assertUnread(depsBlock("else"))

    fun testADepsFunctionWithARescueKeywordIsUnread() = assertUnread(depsKeyword("rescue"))

    fun testADepsFunctionWithACatchKeywordIsUnread() = assertUnread(depsKeyword("catch"))

    fun testADepsFunctionWithAnElseKeywordIsUnread() = assertUnread(depsKeyword("else"))

    fun testAProjectFunctionWithARescueBlockHasUnknownDeps() = assertUnread(projectBlock("rescue"))

    fun testAProjectFunctionWithACatchBlockHasUnknownDeps() = assertUnread(projectBlock("catch"))

    fun testAProjectFunctionWithAnElseBlockHasUnknownDeps() = assertUnread(projectBlock("else"))

    fun testAProjectFunctionWithARescueKeywordHasUnknownDeps() = assertUnread(projectKeyword("rescue"))

    fun testAProjectFunctionWithACatchKeywordHasUnknownDeps() = assertUnread(projectKeyword("catch"))

    fun testAProjectFunctionWithAnElseKeywordHasUnknownDeps() = assertUnread(projectKeyword("else"))

    /** An `after` block runs for its effect and leaves the function's value alone. */
    fun testAFunctionWithAnAfterBlockKeepsItsBodysList() {
        assertDeclared(setOf("foo"), "def project, do: [deps: deps()]\n\ndefp deps do\n  [{:foo, \"~> 1.0\"}]\nafter\n  :ok\nend")
    }

    fun testADepsDefinerThatReturnsNoListIsUnread() {
        assertUnread("def project, do: [deps: deps()]\n\ndefp deps, do: fetch()")
    }

    /** A `project/0` whose `deps:` is the bare name its clause [binding]s first, beside a `deps/0` of another list. */
    private fun bound(binding: String) =
        "def project do\n${binding.prependIndent("  ")}\n  [deps: deps]\nend\n\ndefp deps, do: [{:ghost, \"~> 1.0\"}]"

    private fun depsBlock(handler: String) =
        "def project, do: [deps: deps()]\n\ndefp deps do\n  []\n$handler\n  _ -> [{:foo, \"~> 1.0\"}]\nend"

    private fun depsKeyword(handler: String) =
        "def project, do: [deps: deps()]\n\ndefp deps, do: [], $handler: (_ -> [{:foo, \"~> 1.0\"}])"

    private fun projectBlock(handler: String) =
        "def project do\n  [app: :a, deps: deps()]\n$handler\n  _ -> [app: :b, deps: [{:foo, \"~> 1.0\"}]]\nend\n\ndefp deps, do: []"

    private fun projectKeyword(handler: String) =
        "def project, do: [app: :a, deps: deps()], $handler: (_ -> [app: :b, deps: [{:foo, \"~> 1.0\"}]])\n\ndefp deps, do: []"

    private fun inModule(body: String) = "defmodule Sample.MixProject do\n${body.prependIndent("  ")}\nend\n"

    /** The apps the watcher gathers from a `project/0` and `deps/0` of [body]. */
    private fun gatheredApps(body: String): Set<String> = gathered(inModule(body)).depSet.map { it.application }.toSet()

    /** A [DepGatherer] that visited a `mix.exs` of [text]. */
    private fun gathered(text: String): DepGatherer {
        val psiFile = myFixture.configureByText("mix.exs", text)

        return DepGatherer().also { gatherer -> ReadAction.run<Throwable> { psiFile.accept(gatherer) } }
    }

    private fun assertDeclared(expected: Set<String>, body: String) =
        assertEquals(ProjectKeywords.Reading.Written(expected), declared(body))

    private fun assertUnread(body: String) = assertEquals(ProjectKeywords.Reading.Unknown, declared(body))

    private fun declared(body: String): ProjectKeywords.Reading<Set<String>> =
        declaredIn("defmodule Sample.MixProject do\n  use Mix.Project\n\n" + body.trimIndent().prependIndent("  ") + "\nend\n")

    private fun declaredIn(text: String): ProjectKeywords.Reading<Set<String>> {
        val mixExs = myFixture.configureByText("mix.exs", text) as ElixirFile

        return ReadAction.compute<ProjectKeywords.Reading<Set<String>>, Throwable> { DepGatherer.declared(mixExs) }
    }
}
