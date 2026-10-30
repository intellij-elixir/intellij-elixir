package org.elixir_lang.expander

import com.intellij.psi.PsiElement
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.util.system.OS
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.call.Call
import org.elixir_lang.snapshot.LegElixirProjectDescriptor

/** What an expansion records of how a use reaches a definition: the `import` behind each import, a head's delegation. */
class ReachRecordTest : PlatformTestCase() {
    private val holder: ProjectModules get() = ProjectModules.getInstance(project)

    override fun getProjectDescriptor(): LightProjectDescriptor = LegElixirProjectDescriptor

    fun testAnImportKeepsItsCall() {
        val file = file("import.ex", "defmodule A do\n  import Map, only: [keys: 1]\n  def f(m), do: keys(m)\nend\n")

        onTheLeg { inputs ->
            assertEquals(
                "Elixir.Map `import Map, only: [keys: 1]` | Elixir.Kernel none",
                imports(file, "keys(m)", inputs),
            )
        }
    }

    fun testALaterImportOfTheSameModuleKeepsTheLaterCall() {
        val file = file(
            "reimport.ex",
            "defmodule A do\n  import Map, only: [keys: 1]\n  import Map, only: [values: 1]\n  def f(m), do: values(m)\nend\n",
        )

        onTheLeg { inputs ->
            assertEquals(
                "Elixir.Map `import Map, only: [values: 1]` | Elixir.Kernel none",
                imports(file, "values(m)", inputs),
            )
        }
    }

    fun testTheKernelImportADefprotocolBuildsKeepsTheDefprotocol() {
        val file = file("protocol.ex", "defprotocol P do\n  @doc \"a\" <> \"b\"\n  def f(x)\nend\n")

        onTheLeg { inputs ->
            assertEquals(
                "Elixir.Kernel `defprotocol P do\n  @doc \"a\" <> \"b\"\n  def f(x)\nend`",
                imports(file, "\"a\" <> \"b\"", inputs),
            )
        }
    }

    fun testEachDelegatingHeadKeepsItsTarget() {
        val file = file(
            "delegate.ex",
            """
            defmodule A do
              defdelegate keys(m), to: Map
              defdelegate size(m), to: Map, as: :map_size
              defdelegate pad(a, b \\ 1), to: Target
              def plain(x), do: x
            end
            """.trimIndent(),
        )

        onTheLeg { inputs ->
            assertEquals(
                "keys/1: Elixir.Map.keys\nsize/1: Elixir.Map.map_size\npad/2: Elixir.Target.pad\npad/1: -\nplain/1: -",
                heads(file, "Elixir.A", inputs),
            )
        }
    }

    fun testADelegatingClauseAfterADefKeepsItsTarget() {
        val file = file("clauses.ex", "defmodule A do\n  def g(:a), do: :own\n  defdelegate g(x), to: Target, as: :f\nend\n")

        onTheLeg { inputs -> assertEquals("g/1: -, Elixir.Target.f", heads(file, "Elixir.A", inputs)) }
    }

    /** Elixir 1.20.4 runs the override, `{:mine, 1}`, and through `super` the delegation, `{:wrapped, {:target, 1}}`. */
    fun testAnOverriddenDelegationIsNoLongerOne() {
        val file = file(
            "overridden.ex",
            """
            defmodule D do
              defdelegate f(x), to: Target
              defoverridable f: 1
              def f(x), do: {:mine, x}
            end

            defmodule S do
              defdelegate f(x), to: Target
              defoverridable f: 1
              def f(x), do: {:wrapped, super(x)}
            end
            """.trimIndent(),
        )

        onTheLeg { inputs ->
            assertEquals("f/1: -", heads(file, "Elixir.D", inputs))
            assertEquals("f (overridable 1)/1: Elixir.Target.f\nf/1: -", heads(file, "Elixir.S", inputs))
        }
    }

    fun testADelegationWhoseStoreRaisesIsNotKept() {
        val file = file("raises.ex", "defmodule A do\n  defmacro f(x), do: x\n  defdelegate f(x), to: Target\nend\n")

        onTheLeg { inputs ->
            val module = holder.expansion(file, inputs).modules.single()

            assertEquals("changed_kind", (module.ended as ExpansionResult.Ended.Raised).error.kind)
            assertEquals("f/1: -", heads(file, "Elixir.A", inputs))
        }
    }

    /** Each entry of the env at [use] that imports names, with the text of the call that made it. */
    private fun imports(file: ElixirFile, use: String, inputs: ProjectModules.Inputs): String =
        holder.indexed(file, inputs).envs.at(call(file, use).textRange).functions.joinToString(" | ") { imports ->
            imports.module + " " + (imports.origin?.let { "`${it.substring(file.text)}`" } ?: "none")
        }

    /** Each definition of [module] in [file], with the target of each of its heads, `-` for a head that delegates nothing. */
    private fun heads(file: ElixirFile, module: String, inputs: ProjectModules.Inputs): String =
        holder.expansion(file, inputs).modules.single { it.module == module }.table.entries.entries.joinToString("\n") { (nameArity, entry) ->
            "${nameArity.name}/${nameArity.arity}: " +
                entry.heads.joinToString(", ") { head -> head.delegation?.let { "${it.module}.${it.function}" } ?: "-" }
        }

    private fun onTheLeg(assertion: (ProjectModules.Inputs) -> Unit) = assertion(ProjectModules.Inputs(legLevel(), legKernel, OS.CURRENT))

    private fun file(name: String, text: String): ElixirFile = myFixture.addFileToProject(name, text) as ElixirFile

    private fun call(file: ElixirFile, text: String): Call {
        val offset = file.text.indexOf(text)
        var element: PsiElement? = file.findElementAt(offset)
        while (element != null && !(element is Call && element.textRange.startOffset == offset && element.text == text)) {
            element = element.parent
        }

        return element as Call
    }
}
