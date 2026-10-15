package org.elixir_lang.expander

import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.PsiReferenceService
import com.intellij.psi.PsiReferenceService.Hints.NO_HINTS
import com.intellij.testFramework.LightProjectDescriptor
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.call.Call
import org.elixir_lang.reference.Callable
import org.elixir_lang.snapshot.LegElixirProjectDescriptor

/** The env [ProjectModules.envIndex] gives at a use: the env a re-expansion of the file gives the use's node. */
class EnvIndexTest : PlatformTestCase() {
    private val holder: ProjectModules get() = ProjectModules.getInstance(project)

    override fun getProjectDescriptor(): LightProjectDescriptor = LegElixirProjectDescriptor

    override fun getTestDataPath(): String = "testData/org/elixir_lang"

    fun testEveryUseHasTheReexpansionsEnv_1_20_4() = assertEveryUseHasTheReexpansionsEnv(inputs("1.20.4"))

    fun testEveryUseHasTheReexpansionsEnv_1_11_4() = assertEveryUseHasTheReexpansionsEnv(inputs("1.11.4"))

    fun testADefmoduleCallGetsTheTopEnv() {
        val inputs = inputs("1.20.4")
        val file = file("top.ex", "defmodule A do\n  def f, do: :ok\nend\n")
        val index = holder.indexed(file, inputs).envs

        assertEquals(Env.empty(inputs.level, inputs.kernel), index.at(call(file, "defmodule A").textRange))
        assertEquals("Elixir.A", index.at(call(file, "def f").textRange).module)
    }

    fun testANodeAfterAnErrorGetsTheTopEnv() {
        val inputs = inputs("1.20.4")
        val file = file("after_error.ex", "defmodule A do\n  u()\nend\n\ndefmodule B do\n  def g, do: :ok\nend\n")
        assertTrue(holder.expansion(file, inputs).modules.single().ended.raises)

        assertEquals(Env.empty(inputs.level, inputs.kernel), holder.indexed(file, inputs).envs.at(call(file, "def g").textRange))
    }

    /**
     * A node after a stop, in the node the stopping statement is in, takes the env that statement was entered in, which
     * the statements before it left; one a nested scope made is not in it. A module that raises ends its file, so each
     * stop is in a file of its own.
     */
    fun testANodeAfterAStopTakesTheEnvOfItsStoppingStatement() {
        val inputs = inputs("1.20.4")
        val byAlias = file(
            "by_alias.ex",
            "defmodule ByAlias do\n  def go(x) do\n    alias T, as: D\n    fn -> alias T, as: F end\n    1.f()\n    D.f(x)\n  end\nend\n",
        )
        val byImport = file(
            "by_import.ex",
            "defmodule T do\n  def h(y), do: y\nend\n\ndefmodule ByImport do\n  def go(x) do\n    import T\n    1.f()\n    h(x)\n  end\nend\n",
        )
        val inBody = file("in_body.ex", "defmodule InBody do\n  alias T, as: E\n  1.f()\n  def late(x), do: E.f(x)\nend\n")
        val inIf = file(
            "in_if.ex",
            "defmodule InIf do\n  def go(x) do\n    alias T, as: D\n    if x do\n      alias T, as: G\n      1.f()\n    end\n\n    D.f(x)\n  end\nend\n",
        )

        assertEquals("aliases [Elixir.D] imports []", directives(byAlias, "D.f(x)", inputs))
        assertEquals("aliases [] imports [Elixir.T]", directives(byImport, "h(x)", inputs))
        assertEquals("aliases [Elixir.E] imports []", directives(inBody, "E.f(x)", inputs))
        assertEquals("aliases [Elixir.D] imports []", directives(inIf, "D.f(x)", inputs))
    }

    /** The aliases and the imports other than `Kernel`'s in the env at the call in [file] that starts with [prefix]. */
    private fun directives(file: ElixirFile, prefix: String, inputs: ProjectModules.Inputs): String {
        val env = holder.indexed(file, inputs).envs.at(call(file, prefix).textRange)

        return "aliases ${env.aliases.map { it.alias }} imports ${env.functions.map { it.module }.filter { it != "Elixir.Kernel" }}"
    }

    private fun assertEveryUseHasTheReexpansionsEnv(inputs: ProjectModules.Inputs) {
        val files = listOf("psi/callable_declaration", "snapshot/inputs").flatMap { directory ->
            val root = myFixture.copyDirectoryToProject(directory, directory)
            val files = mutableListOf<ElixirFile>()
            VfsUtilCore.iterateChildrenRecursively(root, null) { virtualFile ->
                (PsiManager.getInstance(project).findFile(virtualFile) as? ElixirFile)?.let(files::add)
                true
            }
            files
        }
        val uses = files.flatMap { file -> uses(file).map { file to it } }
        val differing = uses.filter { (file, use) ->
            holder.indexed(file, inputs).envs.at(use.textRange) != reexpanded(file, use.textRange, inputs)
        }

        assertEquals("", differing.joinToString("\n") { (file, use) -> "${file.name} ${use.textRange} `${use.text}`" })
        assertTrue("${uses.size} uses", uses.size > 200)
    }

    /** The env of the innermost node containing [range] that an expansion of [file] stopping at [range] enters. */
    private fun reexpanded(file: ElixirFile, range: TextRange, inputs: ProjectModules.Inputs): Env {
        var innermost: Pair<TextRange, Env>? = null
        val observer = ExpansionObserver { node, _, env ->
            val origin = node.meta.origin
            if (origin.contains(range)) {
                val current = innermost?.first
                if (current == null || (current != origin && current.contains(origin))) innermost = origin to env
                if (origin == range) throw Reached()
            }
        }
        try {
            holder.observe(file, inputs, observer)
        } catch (_: Reached) {
        }

        return innermost?.second ?: Env.empty(inputs.level, inputs.kernel)
    }

    private class Reached : RuntimeException(null, null, false, false)

    private fun uses(file: ElixirFile): List<Call> {
        val uses = mutableListOf<Call>()
        file.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                super.visitElement(element)

                if (PsiReferenceService.getService().getReferences(element, NO_HINTS).any { it is Callable }) {
                    uses += element as Call
                }
            }
        })

        return uses
    }

    private fun file(name: String, text: String): ElixirFile = myFixture.addFileToProject(name, text) as ElixirFile

    private fun call(file: ElixirFile, prefix: String): Call {
        val offset = file.text.indexOf(prefix)
        var element: PsiElement? = file.findElementAt(offset)
        while (element != null && !(element is Call && element.textRange.startOffset == offset && element.text.startsWith(prefix))) {
            element = element.parent
        }

        return element as Call
    }

    private fun inputs(version: String) = ProjectModules.Inputs(ElixirLanguageLevel.of(version), legKernel)
}
