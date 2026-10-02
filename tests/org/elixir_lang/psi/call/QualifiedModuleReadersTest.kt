package org.elixir_lang.psi.call

import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.gotoDeclarationTargetsAtCaret
import org.elixir_lang.code_insight.psiUsagesAtCaret

/**
 * The readers of a qualified call's module, each through a gesture, with the qualifier spelled as a quoted atom and a
 * plain-spelled control.
 */
class QualifiedModuleReadersTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
    }

    /** `psi/Definition.kt:39` through the stub, and `Module.is`: a module defined through `:"Elixir.Kernel"`. */
    fun testQuotedKernelDefmoduleDefinesAModule() {
        myFixture.configureByText(
            "quoted_kernel_defmodule.ex",
            ":\"Elixir.Kernel\".defmodule Foo do\n  def f, do: 1\nend\n\ndefmodule Caller do\n  def g, do: Foo.f<caret>()\nend\n"
        )
        assertDestinationText("def f, do: 1")
    }

    fun testKernelDefmoduleDefinesAModule() {
        myFixture.configureByText(
            "kernel_defmodule.ex",
            "Kernel.defmodule Foo do\n  def f, do: 1\nend\n\ndefmodule Caller do\n  def g, do: Foo.f<caret>()\nend\n"
        )
        assertDestinationText("def f, do: 1")
    }

    fun testKernelDefDefinesAFunction() {
        myFixture.configureByText(
            "kernel_def.ex",
            "defmodule M do\n  Kernel.def f, do: 1\nend\n\ndefmodule Caller do\n  def g, do: M.f<caret>()\nend\n"
        )
        assertDestinationText("Kernel.def f, do: 1")
    }

    fun testGenServerCallNavigatesToHandleCall() {
        myFixture.configureByText(
            "gen_server.ex",
            "defmodule Stack do\n  use GenServer\n\n  def pop(pid), do: GenServer.call(pid, :<caret>pop)\n\n" +
                "  def handle_call(:pop, _from, [head | tail]) do\n    {:reply, head, tail}\n  end\nend\n"
        )
        assertDestinationText("def handle_call(:pop, _from, [head | tail]) do\n    {:reply, head, tail}\n  end")
    }

    /** `CallDefinitionClause.is` (KERNEL): a function defined through `:"Elixir.Kernel"`. */
    fun testQuotedKernelDefDefinesAFunction() {
        myFixture.configureByText(
            "quoted_kernel_def.ex",
            "defmodule M do\n  :\"Elixir.Kernel\".def f, do: 1\nend\n\ndefmodule Caller do\n  def g, do: M.f<caret>()\nend\n"
        )
        assertDestinationText(":\"Elixir.Kernel\".def f, do: 1")
    }

    /** `matchesCallSite`: a call qualified by the module's quoted atom is a call site. */
    fun testQuotedElixirAtomQualifierIsACallSite() {
        myFixture.configureByText(
            "quoted_call_site.ex",
            "defmodule M do\n  def f<caret>, do: 1\nend\n\ndefmodule Caller do\n  def g, do: :\"Elixir.M\".f()\nend\n"
        )
        assertUsages(":\"Elixir.M\".f()")
    }

    /** `matchesCallSite`: a quoted Erlang-style atom and its bare spelling are one module. */
    fun testQuotedErlangAtomQualifierIsACallSite() {
        myFixture.configureByText(
            "quoted_erlang_call_site.ex",
            "defmodule :erl do\n  def f<caret>, do: 1\nend\n\ndefmodule Caller do\n  def g, do: :\"erl\".f()\nend\n"
        )
        assertUsages(":\"erl\".f()")
    }

    /** `GenServerDispatch` (`"GenServer"`): a request sent through `:"Elixir.GenServer"`. */
    fun testQuotedGenServerCallNavigatesToHandleCall() {
        myFixture.configureByText(
            "quoted_gen_server.ex",
            "defmodule Stack do\n  use GenServer\n\n  def pop(pid), do: :\"Elixir.GenServer\".call(pid, :<caret>pop)\n\n" +
                "  def handle_call(:pop, _from, [head | tail]) do\n    {:reply, head, tail}\n  end\nend\n"
        )
        assertDestinationText("def handle_call(:pop, _from, [head | tail]) do\n    {:reply, head, tail}\n  end")
    }

    private fun assertDestinationText(expected: String) {
        val destinations = myFixture.gotoDeclarationTargetsAtCaret()?.mapNotNull { it.destination }
        assertEquals(
            "destinations ${destinations?.map { it.text }}",
            listOf(true),
            destinations?.map { destination ->
                generateSequence(destination) { it.parent }.takeWhile { it !is com.intellij.psi.PsiFile }.any { it.text == expected }
            }
        )
    }

    private fun assertUsages(vararg expected: String) {
        val usages = myFixture.psiUsagesAtCaret(project)
            .filterNot { it.declaration }
            .map { usage ->
                val leaf = usage.file.findElementAt(usage.range.startOffset)!!
                generateSequence(PsiTreeUtil.getParentOfType(leaf, Call::class.java, false)) { it.parent as? Call }
                    .last()
                    .text
            }
        assertEquals(expected.toList(), usages)
    }
}
