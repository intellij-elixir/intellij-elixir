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

    fun testADepsDefinerThatReturnsNoListIsUnread() {
        assertUnread("def project, do: [deps: deps()]\n\ndefp deps, do: fetch()")
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
