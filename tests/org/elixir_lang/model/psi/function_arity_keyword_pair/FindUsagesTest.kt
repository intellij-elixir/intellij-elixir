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

    /** A head with defaults is one function at each arity it declares, so a key naming any of them is a use of it. */
    fun testAKeyNamingAnyArityOfAHeadWithDefaultsIsFound() =
        listOf("def snoc(q, x \\\\ nil), do: {q, x}", "defdelegate snoc(q, x \\\\ nil), to: Target").forEach { declaration ->
            assertEquals(
                declaration,
                listOf("only: [snoc: 1]", "except: [snoc: 1]"),
                keysFound(defaults(declaration).replace("do: snoc(a, b)", "do: sn<caret>oc(a, b)"))
            )
        }

    /** A bodiless head with defaults and the clauses after it are one function, whichever of them the search starts at. */
    fun testAKeyNamingAnArityOnlyTheHeadDeclaresIsFoundFromAClause() =
        assertEquals(
            listOf("only: [snoc: 1]", "except: [snoc: 1]"),
            keysFound(
                defaults("def snoc(q, x \\\\ nil)\n  def snoc(q, nil), do: q\n  def snoc(q, x), do: {q, x}")
                    .replace("def snoc(q, x), do: {q, x}\nend\n\ndefmodule OnlyCaller", "def sn<caret>oc(q, x), do: {q, x}\nend\n\ndefmodule OnlyCaller")
            )
        )

    /** A call at an arity nothing declares offers the delegation, whose uses are the same. */
    fun testAKeyNamingAnyArityOfADelegationIsFoundFromARejectedCall() =
        assertEquals(
            listOf("only: [snoc: 1]", "except: [snoc: 1]"),
            keysFound(
                defaults("defdelegate snoc(q, x \\\\ nil), to: Target") +
                    "\n\ndefmodule Rejected do\n  def calls(a), do: Definer.sn<caret>oc(a, a, a)\nend\n"
            )
        )

    private fun defaults(declaration: String) = """
        defmodule Target do
          def snoc(q, x), do: {q, x}
        end

        defmodule Definer do
          DECLARATION
        end

        defmodule OnlyCaller do
          import Definer, only: [snoc: 1]

          def calls(a), do: snoc(a)
        end

        defmodule ExceptCaller do
          import Definer, except: [snoc: 1]

          def calls(a, b), do: snoc(a, b)
        end
    """.trimIndent().replace("DECLARATION", declaration)

    @Suppress("UnstableApiUsage")
    private fun keysFound(text: String): List<String> {
        myFixture.configureByText("defaults_keys.ex", text)

        return myFixture.singleTargetPsiUsagesAtCaret(project)
            .filterNot { it.declaration }
            .mapNotNull { usage ->
                val element = usage.file.findElementAt(usage.range.startOffset) ?: return@mapNotNull null
                PsiTreeUtil.getParentOfType(element, QuotableKeywordPair::class.java)
                    ?.takeIf { PsiTreeUtil.isAncestor(it.keywordKey, element, false) }
                    ?.let { pair -> PsiTreeUtil.getParentOfType(pair, QuotableKeywordPair::class.java)?.text }
            }
            .sortedByDescending { it.startsWith("only") }
    }

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
