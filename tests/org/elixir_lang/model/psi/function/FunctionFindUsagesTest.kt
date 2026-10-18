package org.elixir_lang.model.psi.function

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.find.usages.api.PsiUsage
import com.intellij.psi.PsiElement
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.assertShowUsagesChosenAtCaret
import org.elixir_lang.code_insight.chosenTargetPsiUsagesAtCaret
import org.elixir_lang.code_insight.searchTargetsAtCaret
import org.elixir_lang.code_insight.singleTargetPsiUsagesAtCaret
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.call.Call

/**
 * Behavior-level tests for forward Find/Show Usages: invoking Find Usages on a `def` lists the
 * **call sites** that dispatch to it. Mirrors [org.elixir_lang.model.psi.protocol.ProtocolFunctionFindUsagesTest] but for regular
 * module functions rather than protocol functions.
 */
class FunctionFindUsagesTest : PlatformTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/model/psi/function"

    /** Find Usages at a call of a one-clause function offers its Symbol alone, not the `def` call beside it. */
    fun testCallOfAOneClauseFunctionOffersNoSecondTarget() {
        myFixture.configureByText("m.ex", "defmodule M do\n  def f(x), do: x\n\n  def run, do: <caret>f(1)\nend\n")
        val reference = TargetElementUtil.findReference(myFixture.editor, myFixture.caretOffset)
            ?: error("no reference at the call")

        assertEquals(emptyList<PsiElement>(), TargetElementUtil.getInstance().getTargetCandidates(reference).toList())
    }

    fun testSameModuleUnqualifiedCallSiteIsFound() {
        assertTrue(
            "Expected the unqualified same-module call site `perform(value)` among the function's usages",
            callSiteUsageCount("usages_same_module.ex") >= 1
        )
    }

    fun testQualifiedCallSiteIsFound() {
        assertTrue(
            "Expected the qualified call site `UsagesQualified.perform(value)` among the function's usages",
            callSiteUsageCount("usages_qualified.ex") >= 1
        )
    }

    /** A capture is a use of the function, so Find Usages on the `def` must list the capture site. */
    fun testCaptureSiteIsFound() {
        assertTrue(
            "Expected the capture site `&UsagesCapture.perform/1` among the function's usages",
            callSiteUsageCount("usages_capture.ex") >= 1
        )
    }

    fun testApplyCallSiteIsFound() {
        assertTrue(
            "Expected the `apply(ApplyTarget, :reverse, [...])` call site among the function's usages",
            callSiteUsageCount("usages_apply.ex") >= 1
        )
    }

    @Suppress("UnstableApiUsage")
    fun testApplyCallSiteIsCountedOnce() {
        val usages = psiUsages("usages_apply.ex")
            .filterNot { it.declaration }
            .filter { it.file.name == "usages_apply.ex" }
            .filter { usage ->
                val line = usage.file.text.substring(0, usage.range.startOffset).count { it == '\n' } + 1
                line == 7
            }

        assertEquals(
            "Expected exactly one usage on the apply MFA atom line",
            1,
            usages.size
        )
    }

    fun testErlangQualifiedCallSiteIsNotCountedYet() {
        assertEquals(
            "The Erlang-style qualified call site `:erlang.sqrt(value)` is still an uncovered gap",
            0,
            callSiteUsageCount("usages_erlang_qualified.ex")
        )
    }

    /** A bare call reaches `Kernel` without an `import`. */
    fun testBareCallToAKernelFunctionIsFound() {
        myFixture.addFileToProject("use.ex", "defmodule Use do\n  def run(x), do: kernel_only(x)\nend\n")
        myFixture.configureByText("kernel.ex", "defmodule Kernel do\n  def <caret>kernel_only(x), do: x\nend\n")

        assertEquals(1, callSiteCount(myFixture.singleTargetPsiUsagesAtCaret(project)))
    }

    fun testRecursiveFunctionIncludesFamilyDeclarationsAndCallSite() {
        val (totalCount, callSiteCount) = functionUsageCounts("recursive_family.ex")
        assertEquals(3, totalCount)
        assertEquals(1, callSiteCount)
    }

    @Suppress("UnstableApiUsage")
    fun testSpecUsageIsLabeledAsSpecificationNotFunctionCall() {
        val usages = psiUsages("usages_specification.ex")
            .filterNot { it.declaration }

        val specificationUsages = usages.filter { it.usageType?.toString() == "Specification" }
        assertTrue("Expected at least one @spec usage grouped as Specification", specificationUsages.isNotEmpty())
        assertTrue(
            "Expected a regular executable call site usage as well",
            usages.any { it.usageType?.toString() == "Function call" }
        )
    }

    /**
     * Ctrl-Click on a function definition - a declaration / SearchTarget - the
     * "Go To Declaration or Usages" handler (`GotoDeclarationOrUsageHandler2`) should choose
     * **Show Usages** rather than doing nothing or navigating to itself.
     */
    fun testCtrlClickOnFunctionDefChoosesShowUsages() {
        myFixture.configureByFiles("ctrl_click_def.ex")
        myFixture.assertShowUsagesChosenAtCaret()
    }

    /** A `defdelegate` whose `to:` does not resolve defines a function of its module, which its local calls use. */
    fun testUnresolvedDelegationLocalCallIsFoundFromTheHead() =
        assertUnresolvedDelegationUsages("defdelegate run(x), to: Missing")

    fun testUnresolvedDelegationLocalCallIsFoundFromTheLocalCall() =
        assertUnresolvedDelegationUsages("def go(x), do: run(x)")

    /** A guard's clauses and the uses of it are one function family, whichever kind its Symbol is. */
    @Suppress("UnstableApiUsage")
    fun testDefguardFindsItsClausesAndItsUses() {
        myFixture.addFileToProject(
            "use.ex",
            "defmodule U do\n  require G\n\n  def run(x) when G.small(x), do: x\nend\n"
        )
        myFixture.configureByText(
            "g.ex",
            "defmodule G do\n  defguard <caret>small(x) when is_integer(x) and x < 10\n  defguard small(x) when is_float(x) and x < 10.0\n\n  def local(x) when small(x), do: x\nend\n"
        )

        val lines = myFixture.singleTargetPsiUsagesAtCaret(project)
            .map { usage ->
                val text = usage.file.text
                "${usage.file.name}: ${text.lines()[text.take(usage.range.startOffset).count { it == '\n' }].trim()}"
            }
            .sorted()

        assertEquals(
            listOf(
                "g.ex: def local(x) when small(x), do: x",
                "g.ex: defguard small(x) when is_float(x) and x < 10.0",
                "g.ex: defguard small(x) when is_integer(x) and x < 10",
                "use.ex: def run(x) when G.small(x), do: x"
            ),
            lines
        )
    }

    /** A `@spec` of a function with defaults names what its head declares, so Find Usages lists what the head's does. */
    fun testSpecOfAFunctionWithDefaultsFindsWhatItsHeadFinds() {
        myFixture.addFileToProject(
            "use.ex",
            """
            defmodule Use do
              import M, only: [x: 1, x: 2]

              def call(a, b), do: {x(a), M.x(a, b)}
              def capture, do: {&M.x/1, &M.x/2}
            end
            """.trimIndent() + "\n"
        )
        myFixture.configureByText(
            "m.ex",
            """
            defmodule M do
              @spec <caret>x(term, term) :: term
              def x(a, b \\ nil), do: {a, b}
            end
            """.trimIndent() + "\n"
        )
        val fromSpec = usageLinesAtCaret()
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("def x") + "def ".length)
        val fromHead = usageLinesAtCaret()

        assertTrue("the head's usages include its calls", fromHead.any { it.endsWith("def call(a, b), do: {x(a), M.x(a, b)}") })
        assertEquals(fromHead, fromSpec)
    }

    /** `def` is a call to `Kernel.def/2`, so its keyword is a use of it, as a `defdelegate` keyword is of its macro. */
    fun testDefKeywordIsAUseOfKernelDef() {
        myFixture.addFileToProject("use.ex", "defmodule Use do\n  def run(x), do: x\nend\n")
        myFixture.configureByText("kernel.ex", "defmodule Kernel do\n  defmacro <caret>def(call, expr), do: {call, expr}\nend\n")

        val lines = usageLinesAtCaret()

        assertTrue("the def keyword: $lines", lines.any { it.startsWith("use.ex:") && it.endsWith("def run(x), do: x") })
    }

    /** A `defdelegate` with defaults defines each arity, and a call at either is a use of the other. */
    fun testDelegationWithDefaultsAtOtherArity() {
        myFixture.addFileToProject("t.ex", "defmodule T do\n  def f(a, b \\\\ nil), do: {a, b}\nend\n")
        myFixture.addFileToProject("use.ex", "defmodule Use do\n  def run(x), do: M.f(x)\nend\n")
        myFixture.configureByText("m.ex", "defmodule M do\n  defdelegate f(a, b \\\\ nil), to: T\n\n  def go(x), do: <caret>f(x, 1)\nend\n")

        val lines = usageLinesAtCaret().map { it.substringAfter(": ") }

        assertTrue("the call at f/1: $lines", "def run(x), do: M.f(x)" in lines)
    }

    /** An `EEx.function_from_string` function is one function at its arity: a call of another of its name is not its. */
    fun testEExFunctionAtOtherArity() {
        eex()
        myFixture.addFileToProject("use.ex", "defmodule Use do\n  def run, do: T.hello(1)\n  def other, do: T.hello(1, 2)\nend\n")
        myFixture.configureByText(
            "t.ex",
            "defmodule T do\n  require EEx\n  EEx.function_from_string(:def, :hel<caret>lo, \"<%= a %>\", [:a])\n" +
                "  EEx.function_from_string(:def, :hello, \"<%= a %><%= b %>\", [:a, :b])\nend\n"
        )

        val lines = usageLinesAtCaret().map { it.substringAfter(": ") }

        assertTrue("the call at its arity: $lines", "def run, do: T.hello(1)" in lines)
        assertFalse("the call at the other's arity: $lines", "def other, do: T.hello(1, 2)" in lines)
    }

    /** A local call of an `EEx.function_from_string` function in its own module is a use of it. */
    fun testEExFunctionLocalCallIsFound() {
        eex()
        myFixture.configureByText(
            "t.ex",
            "defmodule T do\n  require EEx\n  EEx.function_from_string(:def, :hel<caret>lo, \"<%= a %>\", [:a])\n\n" +
                "  def run, do: hello(1)\nend\n"
        )

        val lines = usageLinesAtCaret().map { it.substringAfter(": ") }

        assertTrue("the local call: $lines", "def run, do: hello(1)" in lines)
    }

    /** A function whose arity can't be known takes any, so calls of it at any arity, here or qualified, are its uses. */
    fun testEExFunctionOfUnknownArityCallsAreFound() {
        eex()
        myFixture.addFileToProject("caller.ex", "defmodule Caller do\n  def run, do: T.hello(1)\nend\n")
        myFixture.configureByText(
            "t.ex",
            "defmodule T do\n  require EEx\n  @args [:a]\n  EEx.function_from_string(:def, :hello, \"<%= a %>\", @args)\n\n" +
                "  def run, do: hel<caret>lo(1)\nend\n"
        )

        val lines = usageLinesAtCaret().map { it.substringAfter(": ") }

        assertTrue("the local call: $lines", "def run, do: hello(1)" in lines)
        assertTrue("the qualified call: $lines", "def run, do: T.hello(1)" in lines)
    }

    /** A helper in a `defimpl` is used only in that `defimpl`, whatever form declares another `defimpl`'s helper. */
    fun testHelperIsNotUsedFromAnotherDefimplsEExHelper() {
        eex()
        myFixture.configureByText(
            "p.ex",
            "defprotocol P do\n  def run(t)\nend\n\n" +
                "defimpl P, for: A do\n  def run(t), do: hel<caret>per(t)\n  defp helper(x), do: x\nend\n\n" +
                "defimpl P, for: B do\n  require EEx\n  EEx.function_from_string(:defp, :helper, \"<%= x %>\", [:x])\n" +
                "  def run(b), do: helper(b)\nend\n"
        )

        val lines = usageLinesAtCaret().map { it.substringAfter(": ") }

        assertTrue("A's call: $lines", "def run(t), do: helper(t)" in lines)
        assertFalse("B's call: $lines", "def run(b), do: helper(b)" in lines)
    }

    /** From an EEx-declared helper in a `defimpl`, its own `defimpl`'s call is its use and another's is not. */
    fun testEExHelperInADefimplFindsOnlyItsOwnCall() {
        eex()
        myFixture.configureByText(
            "p.ex",
            "defprotocol P do\n  def run(t)\nend\n\n" +
                "defimpl P, for: A do\n  def run(t), do: helper(t)\n  defp helper(x), do: x\nend\n\n" +
                "defimpl P, for: B do\n  require EEx\n  EEx.function_from_string(:defp, :hel<caret>per, \"<%= x %>\", [:x])\n" +
                "  def run(b), do: helper(b)\nend\n"
        )

        val lines = usageLinesAtCaret().map { it.substringAfter(": ") }

        assertTrue("B's call: $lines", "def run(b), do: helper(b)" in lines)
        assertFalse("A's call: $lines", "def run(t), do: helper(t)" in lines)
    }

    /** A call or head only a function of unknown arity can take is not a use of a definition at another arity. */
    fun testCallOnlyAFunctionOfUnknownArityTakesIsNotAnothersUse() {
        eex()
        myFixture.addFileToProject("m.ex", "defmodule M do\n  defdelegate render(a, b), to: T\nend\n")
        myFixture.configureByText(
            "t.ex",
            "defmodule T do\n  require EEx\n  @args [:a, :b]\n  EEx.function_from_string(:def, :render, \"<%= a %>\", @args)\n\n" +
                "  def ren<caret>der(x), do: x\n  def one, do: render(1)\n  def two, do: render(1, 2)\nend\n"
        )

        val lines = usageLinesAtCaret().map { it.substringAfter(": ") }

        assertTrue("the call at its arity: $lines", "def one, do: render(1)" in lines)
        assertFalse("the call at another arity: $lines", "def two, do: render(1, 2)" in lines)
        assertFalse("the delegation at another arity: $lines", "defdelegate render(a, b), to: T" in lines)
    }

    /**
     * A call both a definition and a function of unknown arity can take offers both as targets, whichever is written
     * first: the definition's lists the calls at its arity alone, the other's every call it can take.
     */
    @Suppress("UnstableApiUsage")
    fun testCallOfADefinitionAndAFunctionOfUnknownArityOffersBoth() {
        eex()
        val unknown = "  @args [:a, :b]\n  EEx.function_from_string(:def, :render, \"<%= a %>\", @args)\n"
        val definition = "  def render(x), do: x\n"

        for (declarations in listOf(unknown + definition, definition + unknown)) {
            myFixture.configureByText(
                "t.ex",
                "defmodule T do\n  require EEx\n$declarations  def one, do: ren<caret>der(1)\n  def two, do: render(1, 2)\nend\n"
            )
            val text = myFixture.file.text
            val targets = myFixture.searchTargetsAtCaret(project)

            assertEquals("targets: $targets", 2, targets.size)

            fun linesOf(declaredAt: String) =
                myFixture.chosenTargetPsiUsagesAtCaret(project) { offered ->
                    offered.single { (it as FunctionSymbol).range.startOffset == text.indexOf(declaredAt) }
                }.map { usage -> text.lines()[text.take(usage.range.startOffset).count { it == '\n' }].trim() }

            val ofDefinition = linesOf("render(x)")
            val ofUnknown = linesOf("render, \"")

            assertTrue("the definition's call at its arity: $ofDefinition", "def one, do: render(1)" in ofDefinition)
            assertFalse("the definition's call at another arity: $ofDefinition", "def two, do: render(1, 2)" in ofDefinition)
            assertTrue("the other's calls: $ofUnknown", listOf("def one, do: render(1)", "def two, do: render(1, 2)").all { it in ofUnknown })
        }
    }

    /** `embed_template(:log, ...)` defines `log_template/1`, whose local call is a use of it. */
    fun testEmbedTemplateNameFindsItsCall() {
        myFixture.addFileToProject(
            "generator.ex",
            "defmodule Mix.Generator do\n  defmacro embed_template(name, contents) do\n    {name, contents}\n  end\nend\n"
        )
        myFixture.configureByText(
            "t.ex",
            "defmodule T do\n  import Mix.Generator\n  embed_template(:lo<caret>g, \"<%= @a %>\")\n\n" +
                "  def run, do: log_template(a: 1)\nend\n"
        )

        val lines = usageLinesAtCaret().map { it.substringAfter(": ") }

        assertTrue("the local call: $lines", "def run, do: log_template(a: 1)" in lines)
    }

    private fun eex() {
        myFixture.addFileToProject(
            "eex.ex",
            "defmodule EEx do\n  defmacro function_from_string(kind, name, source, args \\\\ [], options \\\\ []) do\n" +
                "    {kind, name, source, args, options}\n  end\nend\n"
        )
    }

    @Suppress("UnstableApiUsage")
    private fun usageLinesAtCaret(): List<String> =
        myFixture.singleTargetPsiUsagesAtCaret(project)
            .map { usage ->
                val text = usage.file.text
                "${usage.file.name}:${usage.range.startOffset}: ${text.lines()[text.take(usage.range.startOffset).count { it == '\n' }].trim()}"
            }
            .sorted()

    @Suppress("UnstableApiUsage")
    private fun assertUnresolvedDelegationUsages(caretLine: String) {
        myFixture.configureByFiles("usages_unresolved_delegation.ex")
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf(caretLine) + caretLine.indexOf("run") + 1)

        val lines = myFixture.singleTargetPsiUsagesAtCaret(project)
            .sortedBy { it.range.startOffset }
            .map { usage ->
                val text = usage.file.text
                text.lines()[text.take(usage.range.startOffset).count { it == '\n' }].trim()
            }

        assertEquals(
            listOf(
                "@spec run(term) :: term",
                "defdelegate run(x), to: Missing",
                "def go(x), do: run(x)",
                "def capture, do: &run/1",
                "def call(x), do: Facade.run(x)"
            ),
            lines
        )
    }

    /**
     * Runs on the same user-facing "Declaration or Usages" path (asserted via GTDUOutcome),
     * then reads usages from the resolved SearchTarget query.
     */
    private fun callSiteUsageCount(vararg files: String): Int {
        return functionUsageCounts(*files).second
    }

    private fun functionUsageCounts(vararg files: String): Pair<Int, Int> {
        val usages = psiUsages(*files)
        return usages.size to callSiteCount(usages)
    }

    @Suppress("UnstableApiUsage")
    private fun psiUsages(vararg files: String): List<PsiUsage> {
        myFixture.configureByFiles(*files)
        myFixture.assertShowUsagesChosenAtCaret()
        return myFixture.singleTargetPsiUsagesAtCaret(project)
    }

    @Suppress("UnstableApiUsage")
    private fun callSiteCount(usages: List<PsiUsage>): Int {
        return usages.count { usage ->
            if (usage.declaration) return@count false
            val element = usage.file.findElementAt(usage.range.startOffset) ?: return@count false
            val enclosingCall = generateSequence(element) { e -> e.parent }
                .filterIsInstance<Call>()
                .firstOrNull()
            enclosingCall != null &&
                !CallDefinitionClause.`is`(enclosingCall) &&
                !CallDefinitionClause.isHead(enclosingCall)
        }
    }
}
