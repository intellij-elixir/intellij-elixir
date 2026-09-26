package org.elixir_lang.mix

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.AppExecutorUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.ElixirAccessExpression
import org.elixir_lang.psi.ElixirDoBlock
import java.util.concurrent.TimeUnit

class DepsTest : PlatformTestCase() {
    /** Of a repeated `deps:`, the first is the project's, as `Keyword.get` reads it. */
    fun testTheFirstOfARepeatedDepsKeyIsRead() =
        assertEquals(
            setOf("first"),
            applications(
                "defp deps do\n    [{:first, \"~> 1.0\"}]\n  end\n\n  defp other do\n    [{:second, \"~> 1.0\"}]\n  end",
                "[deps: deps(), deps: other()]"
            )
        )

    /** A dep named by an interpolated atom has no name until the project is compiled, so it is left out. */
    fun testADepWithAnInterpolatedNameIsLeftOut() {
        val psiFile = myFixture.configureByText(
            "mix.exs",
            "defmodule Sample.MixProject do\n  def project do\n    [deps: deps()]\n  end\n\n" +
                "  defp deps do\n    [{:plain, \"~> 1.0\"}, {:\"my_#{suffix()}\", \"~> 1.0\"}]\n  end\nend\n"
        )
        val gatherer = DepGatherer()

        psiFile.accept(gatherer)

        assertEquals(setOf("plain"), gatherer.depSet.map { it.application }.toSet())
    }

    private fun applications(depsFunctions: String, project: String): Set<String> {
        val psiFile = myFixture.configureByText(
            "mix.exs",
            "defmodule Sample.MixProject do\n  def project do\n    $project\n  end\n\n  $depsFunctions\nend\n"
        )
        val gatherer = DepGatherer()

        psiFile.accept(gatherer)

        return gatherer.depSet.map { it.application }.toSet()
    }

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
}
