package org.elixir_lang.model.psi.function_arity_keyword_pair

import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.assertShowUsagesChosenAtCaret
import org.elixir_lang.code_insight.singleTargetPsiUsagesAtCaret
import org.elixir_lang.psi.QuotableKeywordPair
import com.intellij.psi.util.PsiTreeUtil

/**
 * Behavior-level tests for forward Find/Show Usages on the `name: arity` keyword-pair construct:
 * invoking Find Usages on a function/macro `def` (or, for `defoverridable`, on the `@callback`) lists
 * the keyword-**key** occurrences that name it inside `import :only`/`:except`, `@compile :inline`,
 * `@dialyzer`, and `defoverridable` directives.
 *
 * Drives the SAME production search pipeline the Find Usages action uses - the [com.intellij.find.usages.impl.searchTargets]
 * resolved at the caret fed through [com.intellij.find.usages.impl.buildQuery] (which runs `ElixirSymbolUsageSearcher` and yields
 * the very [com.intellij.find.usages.api.PsiUsage]s the tool window would display) - exactly like
 * [org.elixir_lang.model.psi.function.FunctionFindUsagesTest]. Assertions never touch internal
 * resolver classes, so they survive refactoring.
 */
class FindUsagesTest : PlatformTestCase() {
    override fun getTestDataPath(): String =
        "testData/org/elixir_lang/model/psi/function_arity_keyword_pair"

    fun testImportOnlyKeyIsFoundAmongFunctionUsages() {
        assertTrue(
            "Expected the `only: [perform: 0]` keyword key among the function's usages",
            keywordKeyUsageCount("usages_import_only.ex") >= 1
        )
    }

    fun testCompileInlineKeyIsFoundAmongFunctionUsages() {
        assertTrue(
            "Expected the `@compile inline: [perform: 0]` keyword key among the function's usages",
            keywordKeyUsageCount("usages_compile_inline.ex") >= 1
        )
    }

    fun testDialyzerNowarnKeyIsFoundAmongFunctionUsages() {
        assertTrue(
            "Expected the `@dialyzer {:nowarn_function, perform: 0}` keyword key among the function's usages",
            keywordKeyUsageCount("usages_dialyzer_nowarn.ex") >= 1
        )
    }

    fun testDefoverridableKeyIsFoundAmongCallbackUsages() {
        assertTrue(
            "Expected the `defoverridable perform: 0` keyword key among the callback's usages",
            keywordKeyUsageCount("usages_defoverridable.ex", "kernel.ex") >= 1
        )
    }

    /** An `only:` key names a `defdelegate` as it names a clause. */
    fun testKeyNamingDelegation() {
        myFixture.addFileToProject("t.ex", "defmodule T do\n  def f(x), do: x\nend\n")
        myFixture.addFileToProject("caller.ex", "defmodule Caller do\n  import M, only: [f: 1]\nend\n")
        myFixture.configureByText("m.ex", "defmodule M do\n  defdelegate <caret>f(x), to: T\nend\n")

        assertEquals(listOf("caller.ex: import M, only: [f: 1]"), keyLinesAtCaret())
    }

    /** An `only:` key names a `defprotocol` function, which `import` brings in as any other. */
    fun testKeyNamingProtocolFunction() {
        myFixture.addFileToProject("caller.ex", "defmodule Caller do\n  import P, only: [f: 1]\nend\n")
        myFixture.configureByText("p.ex", "defprotocol P do\n  def <caret>f(t)\nend\n")

        assertEquals(listOf("caller.ex: import P, only: [f: 1]"), keyLinesAtCaret())
    }

    /** A key at another arity names another protocol function. */
    fun testKeyAtAnotherArityOfAProtocolFunctionIsNotFound() {
        myFixture.addFileToProject("caller.ex", "defmodule Caller do\n  import P, only: [f: 2]\nend\n")
        myFixture.configureByText("p.ex", "defprotocol P do\n  def <caret>f(t)\n  def f(t, u)\nend\n")

        assertEquals(emptyList<String>(), keyLinesAtCaret())
    }

    /** Each key usage of the symbol at the caret, as its file and line. */
    @Suppress("UnstableApiUsage")
    private fun keyLinesAtCaret(): List<String> =
        myFixture.singleTargetPsiUsagesAtCaret(project)
            .filterNot { it.declaration }
            .filter { usage ->
                val element = usage.file.findElementAt(usage.range.startOffset)
                element != null && generateSequence(element) { it.parent }
                    .filterIsInstance<QuotableKeywordPair>()
                    .any { pair -> PsiTreeUtil.isAncestor(pair.keywordKey, element, false) }
            }
            .map { usage ->
                val text = usage.file.text
                "${usage.file.name}: ${text.lines()[text.take(usage.range.startOffset).count { it == '\n' }].trim()}"
            }
            .sorted()

    /**
     * Runs Find Usages on the symbol at the caret in [files] and counts non-declaration usages whose
     * location is inside the **key** of a [QuotableKeywordPair] - i.e. a `name: arity` keyword-pair
     * occurrence. Restricting to the key (not the whole pair) avoids matching call sites that merely sit
     * inside a keyword-pair *value* such as `def run, do: perform()`.
     */
    @Suppress("UnstableApiUsage")
    private fun keywordKeyUsageCount(vararg files: String): Int {
        myFixture.configureByFiles(*files)
        myFixture.assertShowUsagesChosenAtCaret()

        return myFixture.singleTargetPsiUsagesAtCaret(project)
            .filterNot { it.declaration }
            .count { usage ->
                val element = usage.file.findElementAt(usage.range.startOffset)
                element != null && generateSequence(element) { it.parent }
                    .filterIsInstance<QuotableKeywordPair>()
                    .any { pair -> PsiTreeUtil.isAncestor(pair.keywordKey, element, false) }
            }
    }
}
