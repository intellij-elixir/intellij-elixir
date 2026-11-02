package org.elixir_lang.expander

import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.PsiReferenceService
import com.intellij.psi.PsiReferenceService.Hints.NO_HINTS
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.util.system.OS
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.call.Call
import org.elixir_lang.reference.Callable
import org.elixir_lang.snapshot.LegElixirProjectDescriptor

/** The env [EnvIndex.at] gives at a use: the env a re-expansion of the file gives the use's node. */
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
     * A node after a stop, in the node the stopping statement is in, takes the env as it stood at the stop; one a nested
     * scope made is not in it. A module that raises ends its file, so each stop is in a file of its own.
     */
    fun testANodeAfterAStopTakesTheEnvAtTheStop() {
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

    fun testAStopInAUsesOutput_1_20_4() = assertAStopInAUsesOutput("1.20.4")

    fun testAStopInAUsesOutput_1_11_4() = assertAStopInAUsesOutput("1.11.4")

    private fun assertAStopInAUsesOutput(version: String) =
        assertFollower("aliases [] imports [Elixir.String]", "import String; import S", version = version)

    fun testARaiseInAUsesOutput() = assertFollower("aliases [] imports [Elixir.String]", "import String; 1.f()")

    fun testAnAliasBeforeAStopInAUsesOutput() = assertFollower(
        "aliases [Elixir.D] imports []",
        "alias T, as: D; import S",
        follower = "def g(x), do: D.f(x)",
        at = "D.f(x)",
    )

    fun testAStopInANestedOutput_1_20_4() = assertAStopInANestedOutput("1.20.4")

    fun testAStopInANestedOutput_1_11_4() = assertAStopInANestedOutput("1.11.4")

    private fun assertAStopInANestedOutput(version: String) = assertFollower(
        "aliases [] imports [Elixir.String, Elixir.Integer]",
        "use V; unquote(helpers())",
        helpers = "import String; import S",
        version = version,
    )

    fun testAStopAfterANestedOutput() = assertFollower("aliases [] imports [Elixir.Integer]", "use V; import S")

    fun testAUnitAfterTheBodyStoppedLeavesItsEnvAlone() = assertFollower(
        "aliases [] imports [Elixir.Integer]",
        "import Integer; def f do import String; import S end; import S",
    )

    fun testADefBodyInAMacrosOutput() =
        assertFollower("aliases [] imports []", "def f do import String; import S end")

    fun testADefBodyInAMacrosOutputThenAStopInTheNextDef() {
        val inputs = inputs("1.20.4")
        val file = using("def f do import String; import S end", "def g do import S; trim(\" \") end; def h, do: upcase(\" \")")

        assertSIsStopped(file, inputs)
        assertEquals("aliases [] imports []", directives(file, "trim(\" \")", inputs))
        assertEquals("aliases [] imports []", directives(file, "upcase(\" \")", inputs))
    }

    fun testAFollowerInABranchAfterAStopKeepsTheBranch() {
        val inputs = inputs("1.20.4")
        val file = source("import Integer; if true do import String; import S; trim(\" \") end")

        assertSIsStopped(file, inputs)
        assertEquals("aliases [] imports [Elixir.String, Elixir.Integer]", directives(file, "trim(\" \")", inputs))
    }

    fun testAFollowerAfterANestedStopInABranch() {
        val inputs = inputs("1.20.4")
        val file = source(
            "import Integer; if true do import String; if true do import Float; import S end; trim(\" \") end; upcase(\" \")",
        )

        assertSIsStopped(file, inputs)
        assertEquals("aliases [] imports [Elixir.String, Elixir.Integer]", directives(file, "trim(\" \")", inputs))
        assertEquals("aliases [] imports [Elixir.Integer]", directives(file, "upcase(\" \")", inputs))
    }

    fun testAnIfInAUsesOutput_1_20_4() = assertAScopeInAUsesOutput("if true do import String; import S end", "1.20.4")

    fun testAnIfInAUsesOutput_1_11_4() = assertAScopeInAUsesOutput("if true do import String; import S end", "1.11.4")

    fun testACaseInAUsesOutput() = assertAScopeInAUsesOutput("case 1 do _ -> import String; import S end")

    fun testACondInAUsesOutput() = assertAScopeInAUsesOutput("cond do true -> import String; import S end")

    fun testAFnInAUsesOutput() = assertAScopeInAUsesOutput("_ = fn -> import String; import S end")

    fun testAForInAUsesOutput() = assertAScopeInAUsesOutput("for _ <- [1] do import String; import S end")

    fun testATryInAUsesOutput() = assertAScopeInAUsesOutput("try do import String; import S after :ok end")

    fun testAWithInAUsesOutput() = assertAScopeInAUsesOutput("with _ <- 1 do import String; import S end")

    fun testAReceiveInAUsesOutput() =
        assertAScopeInAUsesOutput("receive do _ -> import String; import S after 0 -> :ok end")

    private fun assertAScopeInAUsesOutput(construct: String, version: String = "1.20.4") =
        assertFollower("aliases [] imports [Elixir.Integer]", "import Integer; $construct", version = version)

    fun testACaseSubjectInAUsesOutputPassesOn() = assertFollower(
        "aliases [] imports [Elixir.String, Elixir.Integer]",
        "import Integer; case (import String; 1) do _ -> import Float; import S end",
    )

    fun testAStopInACaseSubjectInAUsesOutput() = assertFollower(
        "aliases [] imports [Elixir.String, Elixir.Integer]",
        "import Integer; case (import String; import S) do _ -> :ok end",
    )

    fun testAtReachedIsNullAfterAStop() {
        val inputs = inputs("1.20.4")
        val file = file("reached_after_stop.ex", "defmodule A do\n  def go(x) do\n    T.h(x)\n    1.f()\n    D.f(x)\n  end\nend\n")
        val index = holder.indexed(file, inputs).envs
        val before = call(file, "T.h(x)").textRange

        assertEquals(index.at(before), index.atReached(before))
        assertNull(index.atReached(call(file, "D.f(x)").textRange))
    }

    fun testAtReachedIsNullInsideAStoppedNode() {
        val inputs = inputs("1.20.4")
        val file = file("reached_inside_stopped.ex", "defmodule A do\n  def go do\n    quote(location: :other, do: B.f())\n  end\nend\n")

        assertNull(holder.indexed(file, inputs).envs.atReached(call(file, "B.f()").textRange))
    }

    fun testAtReachedIsNullAfterAnEnteredNodeInAStoppedOne() {
        val inputs = inputs("1.20.4")
        val file = file(
            "reached_after_entered.ex",
            "defmodule A do\n  @adapter System.get_env(\"A\")\n  def go, do: @adapter.call(B.f())\nend\n",
        )

        assertNull(holder.indexed(file, inputs).envs.atReached(call(file, "B.f()").textRange))
    }

    /** The aliases and the imports other than `Kernel`'s in the env at the call in [file] that starts with [prefix]. */
    private fun directives(file: ElixirFile, prefix: String, inputs: ProjectModules.Inputs): String {
        val env = holder.indexed(file, inputs).envs.at(call(file, prefix).textRange)

        return "aliases ${env.aliases.map { it.alias }} imports ${env.functions.map { it.module }.filter { it != "Elixir.Kernel" }}"
    }

    /** Asserts [expected] from [directives] at [at] in [follower], after a `use` whose output is [output]. */
    private fun assertFollower(
        expected: String,
        output: String,
        follower: String = "def g, do: trim(\" \")",
        at: String = "trim(\" \")",
        helpers: String? = null,
        version: String = "1.20.4",
    ) {
        val inputs = inputs(version)
        val file = using(output, follower, helpers)

        if ("import S" in output || "import S" in helpers.orEmpty()) assertSIsStopped(file, inputs)
        assertEquals(expected, directives(file, at, inputs))
    }

    private fun assertSIsStopped(file: ElixirFile, inputs: ProjectModules.Inputs) {
        val s = holder.expansion(file, inputs).modules.single { it.module == "Elixir.S" }

        assertTrue("${s.ended}", s.ended is ExpansionResult.Ended.Stopped)
    }

    private fun using(output: String, follower: String, helpers: String? = null): ElixirFile = file(
        "$name.ex",
        "$S\n$V\ndefmodule U do\n  ${helpers?.let { "defp helpers, do: quote(do: ($it))" }.orEmpty()}\n" +
            "  defmacro __using__(_) do\n    quote do\n      $output\n    end\n  end\nend\n" +
            "defmodule M do\n  use U\n  $follower\nend\n",
    )

    private fun source(body: String): ElixirFile = file("$name.ex", "$S\ndefmodule M do\n  $body\nend\n")

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

    /**
     * The env of the innermost node containing [range] that an expansion of [file] stopping at [range] enters. Where the
     * expansion doesn't reach [range], and the last node it entered before [range] in that one stopped, as at an error
     * that ends the module, the env at that stop, as [testANodeAfterAStopTakesTheEnvAtTheStop] pins.
     */
    private fun reexpanded(file: ElixirFile, range: TextRange, inputs: ProjectModules.Inputs): Env {
        var innermost: Pair<TextRange, Env>? = null
        var reached = false
        val entered = LinkedHashMap<TextRange, Env>()
        val stopped = mutableMapOf<TextRange, Expansion.Stop>()
        val stopEnv = mutableMapOf<TextRange, Env?>()
        val observer = object : ExpansionObserver {
            override fun entering(node: ElixirAst, state: ExState, env: Env) {
                val origin = node.meta.origin
                entered.putIfAbsent(origin, env)
                if (origin.contains(range)) {
                    val current = innermost?.first
                    if (current == null || (current != origin && current.contains(origin))) innermost = origin to env
                    if (origin == range) throw Reached()
                }
            }

            override fun left(node: ElixirAst, expansion: Expansion) {
                if (expansion !is Expansion.Stop) return
                val origin = node.meta.origin
                if (stopped.getOrPut(origin) { expansion } === expansion) stopEnv[origin] = expansion.envAtStop
            }
        }
        try {
            holder.observe(file, inputs, observer)
        } catch (_: Reached) {
            reached = true
        }

        val container = innermost ?: return Env.empty(inputs.level, inputs.kernel)
        if (reached) return container.second

        var before: TextRange? = null
        entered.keys
            .filter { container.first.contains(it) && it != container.first && it.endOffset <= range.startOffset }
            .sortedWith(compareBy<TextRange> { it.startOffset }.thenByDescending { it.endOffset })
            .forEach { if (before?.contains(it) != true) before = it }

        return before?.takeIf { it in stopped }?.let { stopEnv[it] ?: entered[it] } ?: container.second
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

    private fun inputs(version: String) = ProjectModules.Inputs(ElixirLanguageLevel.of(version), legKernel, OS.CURRENT)

    private companion object {
        /** Stops in its body: Elixir raises there, so nothing will port it as a success. */
        const val S = "defmodule S do\n  quote(location: :other, do: 1)\nend"
        const val V = "defmodule V do\n  defmacro __using__(_) do\n    quote do\n      import Integer\n    end\n  end\nend"
    }
}
