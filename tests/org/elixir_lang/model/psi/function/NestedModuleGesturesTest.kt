package org.elixir_lang.model.psi.function

import com.intellij.openapi.application.ReadAction
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.psiUsagesAtCaret
import org.elixir_lang.code_insight.renameTargetAtCaret
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.call.Call

/** A module nested in another is named `Outer.Inner` at every site that names it, and `Inner` inside `Outer`. */
class NestedModuleGesturesTest : PlatformTestCase() {
    fun testFindUsagesAtANestedModulesFunction() {
        configure("def <caret>f(0)")

        assertEquals(listOf("caller.ex:2", "caller.ex:3", "nested.ex:3", "nested.ex:4"), usageLines())
    }

    fun testRenameANestedModulesFunction() {
        configure("def <caret>f(0)")

        myFixture.renameTargetAtCaret("g")

        assertRenamedToG()
    }

    /** One clause: an atom names each clause of a function apart, which is not this test's subject. */
    fun testRenameANestedModulesFunctionFromAnApplyAtom() {
        myFixture.addFileToProject("nested.ex", "defmodule Outer do\n  defmodule Inner do\n    def h(a), do: a\n  end\nend\n")
        myFixture.configureByText(
            "caller.ex",
            "defmodule Caller do\n  def run, do: Outer.Inner.h(1)\n  def later, do: apply(Outer.Inner, :<caret>h, [1])\nend\n"
        )

        myFixture.renameTargetAtCaret("g")

        assertTrue("rename left ${fileText("nested.ex")}", "def g(a), do: a" in fileText("nested.ex"))
        assertEquals(listOf("def run, do: Outer.Inner.g(1)", "def later, do: apply(Outer.Inner, :g, [1])"), callerLines())
    }

    fun testRenameAComputedModulesFunctionLeavesItsSiblings() {
        myFixture.configureByText(
            "gen.ex",
            "defmodule Gen do\n  defmacro both(a, b) do\n    quote do\n      defmodule unquote(a) do\n        def <caret>f(x), do: x\n      end\n\n      defmodule unquote(b) do\n        def f(x), do: x\n      end\n    end\n  end\nend\n"
        )

        myFixture.renameTargetAtCaret("g")

        assertTrue(
            "rename left ${fileText("gen.ex")}",
            "def g(x), do: x\n      end\n\n      defmodule unquote(b) do\n        def f(x), do: x" in fileText("gen.ex")
        )
    }

    /** A piped `defmodule` writes no first argument, so its functions key to no module, as on `main`. */
    fun testAPipedModulesFunctionHasNoSymbol() {
        myFixture.configureByText("piped.ex", "Outer |> defmodule do\n  def <caret>f(x), do: x\nend\n")

        val symbols = readAction {
            generateSequence(myFixture.file.findElementAt(myFixture.caretOffset)) { it.parent }
                .filterIsInstance<Call>()
                .first { CallDefinitionClause.`is`(it) }
                .let(FunctionSymbol::fromClause)
        }

        assertEquals(listOf<FunctionSymbol>(), symbols)
    }

    fun testFindUsagesAtANestedProtocolsFunction() {
        configure("def <caret>p(x)")

        assertEquals(listOf("caller.ex:4", "caller.ex:8", "nested.ex:8"), usageLines())
    }

    fun testRenameANestedProtocolsFunction() {
        configure("def <caret>p(x)")

        myFixture.renameTargetAtCaret("q")

        assertTrue("rename left ${fileText("nested.ex")}", "def q(x)\n" in fileText("nested.ex"))
        assertEquals(listOf("def proto, do: Outer.Proto.q(1)", "def q(x), do: x"), callerLines().drop(2))
    }

    /** A delegation without `as:` is one function with its target, whose `def` is listed too. */
    fun testFindUsagesAtANestedDelegation() {
        configureShortAndFull(DELEGATION)

        assertEquals(listOf("outer.ex:3", "outer.ex:6", "t.ex:2", "use.ex:2"), usageLines())
    }

    fun testRenameANestedDelegation() {
        configureShortAndFull(DELEGATION)

        myFixture.renameTargetAtCaret("g")

        assertEquals(
            listOf(
                "defdelegate g(x), to: T",
                "def short, do: Inner.g(1)",
                "def full, do: Outer.Inner.g(1)",
                "def g(x), do: x"
            ),
            listOf("outer.ex", "use.ex", "t.ex").flatMap(::fileLines)
        )
    }

    fun testFindUsagesAtANestedProtocolFunctionCalledByBothNames() {
        configureShortAndFull(PROTOCOL)

        assertEquals(listOf("outer.ex:3", "outer.ex:6", "use.ex:2"), usageLines())
    }

    fun testRenameANestedProtocolFunctionCalledByBothNames() {
        configureShortAndFull(PROTOCOL)

        myFixture.renameTargetAtCaret("g")

        assertEquals(
            listOf("def g(x)", "def short(x), do: P.g(x)", "def full, do: Outer.P.g(1)"),
            listOf("outer.ex", "use.ex").flatMap(::fileLines)
        )
    }

    /** A call at an arity the nested module does not define names `f/1` for Go To, but is no use of it. */
    fun testFindUsagesSkipsANestedCallAtAnotherArity() {
        configureShortAndFull(NEAR_MISS)

        assertEquals(listOf("outer.ex:3", "outer.ex:6"), usageLines())
    }

    fun testRenameSkipsANestedCallAtAnotherArity() {
        configureShortAndFull(NEAR_MISS)

        myFixture.renameTargetAtCaret("g")

        assertEquals(
            listOf(
                "def g(x), do: x",
                "def short, do: Inner.g(1)",
                "def full, do: Outer.Inner.f(1, 2)",
                "def f(x), do: x"
            ),
            listOf("outer.ex", "use.ex", "t.ex").flatMap(::fileLines)
        )
    }

    /** Inside `Outer`, `Inner` is `Outer.Inner`, so its call there is no use of a top-level `Inner`. */
    fun testFindUsagesSkipsAShortCallThatNamesTheNestedModule() {
        configureTopLevelOfTheNestedName()

        assertEquals(listOf("inner.ex:2", "use.ex:2"), usageLines())
    }

    fun testRenameSkipsAShortCallThatNamesTheNestedModule() {
        configureTopLevelOfTheNestedName()

        myFixture.renameTargetAtCaret("g")

        assertEquals(
            listOf("def g(x), do: x", "def f(x), do: x", "def short, do: Inner.f(1)", "def top, do: Inner.g(1)"),
            listOf("inner.ex", "outer.ex", "use.ex").flatMap(::fileLines)
        )
    }

    private fun assertRenamedToG() {
        assertTrue("rename left ${fileText("nested.ex")}", "def g(0), do: 0\n    def g(a), do: a" in fileText("nested.ex"))
        assertEquals(
            listOf("def run, do: Outer.Inner.g(1)", "def later, do: apply(Outer.Inner, :g, [1])"),
            callerLines().take(2)
        )
    }

    /** The `file:line` of each usage Find Usages lists from the caret, declarations included, sorted. */
    @Suppress("UnstableApiUsage")
    private fun usageLines(): List<String> =
        myFixture.psiUsagesAtCaret(project).map { psiUsage ->
            readAction { "${psiUsage.file.name}:${psiUsage.file.text.substring(0, psiUsage.range.startOffset).count { it == '\n' } + 1}" }
        }.distinct().sorted()

    /** `caller.ex`'s lines that call or implement something, trimmed. */
    private fun callerLines(): List<String> = fileText("caller.ex").lines().map { it.trim() }.filter { it.startsWith("def ") }

    /** [name]'s lines that define or call `f` or `g`, trimmed. */
    private fun fileLines(name: String): List<String> =
        fileText(name).lines().map { it.trim() }.filter { it.startsWith("def") && ("f(" in it || "g(" in it) }

    private fun fileText(name: String): String =
        readAction { myFixture.psiManager.findFile(myFixture.findFileInTempDir(name))!!.text }

    private fun <T> readAction(block: () -> T): T = ReadAction.computeBlocking<T, Throwable> { block() }

    /** The two files, with the caret at [nested] in `nested.ex`. */
    private fun configure(nested: String) {
        val callerText = "defmodule Caller do\n" +
            "  def run, do: Outer.Inner.f(1)\n" +
            "  def later, do: apply(Outer.Inner, :f, [1])\n" +
            "  def proto, do: Outer.Proto.p(1)\n" +
            "end\n" +
            "\n" +
            "defimpl Outer.Proto, for: Integer do\n" +
            "  def p(x), do: x\n" +
            "end\n"
        val nestedText = "defmodule Outer do\n" +
            "  defmodule Inner do\n" +
            "    def f(0), do: 0\n" +
            "    def f(a), do: a\n" +
            "  end\n" +
            "\n" +
            "  defprotocol Proto do\n" +
            "    def p(x)\n" +
            "  end\n" +
            "end\n"
        val bare = nested.replace("<caret>", "")
        val at = nestedText.indexOf(bare).also { assertTrue("`$bare` not in the fixture", it >= 0) }

        myFixture.addFileToProject("caller.ex", callerText)
        myFixture.configureByText("nested.ex", nestedText.substring(0, at) + nested + nestedText.substring(at + bare.length))
    }

    /** `Outer` holding [nested]: the nested form, with the caret at its `f`, and the full-name call `use.ex` makes. */
    private fun configureShortAndFull(nested: Pair<String, String>) {
        myFixture.addFileToProject("t.ex", "defmodule T do\n  def f(x), do: x\nend\n")
        myFixture.addFileToProject("use.ex", "defmodule U do\n  def full, do: ${nested.second}\nend\n")
        myFixture.configureByText("outer.ex", "defmodule Outer do\n  ${nested.first}\nend\n")
    }

    /** A top-level `Inner`, with the caret at its `f`, beside `Outer.Inner`, which `Outer` calls by its short name. */
    private fun configureTopLevelOfTheNestedName() {
        myFixture.addFileToProject(
            "outer.ex",
            "defmodule Outer do\n  defmodule Inner do\n    def f(x), do: x\n  end\n\n  def short, do: Inner.f(1)\nend\n"
        )
        myFixture.addFileToProject("use.ex", "defmodule U do\n  def top, do: Inner.f(1)\nend\n")
        myFixture.configureByText("inner.ex", "defmodule Inner do\n  def <caret>f(x), do: x\nend\n")
    }

    private companion object {
        val DELEGATION =
            "defmodule Inner do\n    defdelegate <caret>f(x), to: T\n  end\n\n  def short, do: Inner.f(1)" to
                "Outer.Inner.f(1)"
        val PROTOCOL = "defprotocol P do\n    def <caret>f(x)\n  end\n\n  def short(x), do: P.f(x)" to "Outer.P.f(1)"
        val NEAR_MISS = "defmodule Inner do\n    def <caret>f(x), do: x\n  end\n\n  def short, do: Inner.f(1)" to
            "Outer.Inner.f(1, 2)"
    }
}
