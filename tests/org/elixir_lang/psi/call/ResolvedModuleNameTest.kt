package org.elixir_lang.psi.call

import com.intellij.find.usages.api.PsiUsage
import com.intellij.find.usages.api.UsageOptions
import com.intellij.find.usages.impl.AllSearchOptions
import com.intellij.find.usages.impl.buildQuery
import com.intellij.find.usages.impl.searchTargets
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.model.psi.function.FunctionSymbol
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.Definition
import org.elixir_lang.psi.call.qualification.Qualified
import org.elixir_lang.psi.definition
import java.util.concurrent.Callable

/** A qualified call's module is the one its qualifier quotes to, and a qualifier with no value names no module. */
@Suppress("UnstableApiUsage")
class ResolvedModuleNameTest : PlatformTestCase() {
    fun testQuotedElixirAtom() = assertResolved(":\"Elixir.Kernel\".max(1, 2)", "Kernel")

    fun testCharListQuotedElixirAtom() = assertResolved(":'Elixir.Kernel'.max(1, 2)", "Kernel")

    fun testElixirPrefixedAlias() = assertResolved("Elixir.Kernel.max(1, 2)", "Kernel")

    fun testQuotedErlangAtom() = assertResolved(":\"lists\".max([1])", ":lists")

    fun testErlangAtom() = assertResolved(":lists.max([1])", ":lists")

    fun testElixirAtomNotShapedAsAnAlias() = assertResolved(":\"Elixir.foo\".max([1])", ":Elixir.foo")

    fun testSpacedAlias() = assertResolved("Foo . Bar.max([1])", "Foo.Bar")

    fun testVariableHeadedAlias() = assertResolved("x.Inner.f()", "x.Inner")

    fun testUnquoteHeadedAlias() = assertResolved("unquote(m).Inner.f()", "unquote(m).Inner")

    fun testVariable() = assertResolved("var.f()", "var")

    fun testUnquote() = assertResolved("unquote(m).f()", "unquote(m)")

    fun testModuleHeadedAlias() = assertResolved("__MODULE__.Inner.f()", "__MODULE__.Inner")

    fun testModule() = assertResolved("__MODULE__.f()", "__MODULE__")

    fun testElixirPrefixedCallQualifier() = assertResolved("Elixir.Foo.bar.f()", "Elixir.Foo.bar")

    fun testQuotedElixirKernelDefIsADefinition() {
        myFixture.configureByText("definition.ex", "defmodule M do\n  :\"Elixir.Kernel\".def f(), do: 1\nend\n")
        val call = qualifiedCall("def")

        assertEquals(Definition.PUBLIC_FUNCTION, ReadAction.computeBlocking<Definition?, Throwable> { definition(call) })
    }

    /** A module nested in one with no value is named by its own alias, so `x.Inner.f()` (no value) is no call site of it. */
    fun testNoValueQualifierIsNoCallSiteOfANoValueModule() {
        myFixture.configureByText(
            "no_value.ex",
            "defmodule unquote(n) do\n  defmodule Inner do\n    def f, do: 1\n  end\nend\n\n" +
                "defmodule Caller do\n  def g(x), do: x.Inner.f()\n  def h(m), do: unquote(m).Inner.f()\nend\n"
        )
        val clause = PsiTreeUtil.findChildrenOfType(myFixture.file, Call::class.java)
            .single { CallDefinitionClause.`is`(it) && it.primaryArguments()?.firstOrNull()?.text == "f" }

        assertEquals(
            "f's module",
            listOf("Inner"),
            ReadAction.computeBlocking<List<String>, Throwable> { FunctionSymbol.fromClause(clause).map { it.moduleName } }
        )
        assertEquals("usages of f", emptyList<String>(), callSites(clause))
    }

    /** `x.Inner` and `defmodule unquote(n).Inner` both read `?.Inner`, but Elixir can't know they are one module. */
    fun testNoValueQualifierIsNoCallSiteOfAModuleNamedWithNoValue() {
        myFixture.configureByText(
            "no_value_named.ex",
            "defmodule unquote(n).Inner do\n  def f, do: 1\nend\n\n" +
                "defmodule Caller do\n  def g(x), do: x.Inner.f()\n  def h(m), do: unquote(m).Inner.f()\nend\n"
        )
        val clause = PsiTreeUtil.findChildrenOfType(myFixture.file, Call::class.java)
            .single { CallDefinitionClause.`is`(it) && it.primaryArguments()?.firstOrNull()?.text == "f" }

        assertEquals(
            "f's module",
            listOf("?.Inner"),
            ReadAction.computeBlocking<List<String>, Throwable> { FunctionSymbol.fromClause(clause).map { it.moduleName } }
        )
        assertEquals("usages of f", emptyList<String>(), callSites(clause))
    }

    private fun assertResolved(call: String, expected: String?) {
        myFixture.configureByText("qualified.ex", "defmodule M do\n  def g(x, var, m), do: $call\nend\n")
        val functionName = call.substringBeforeLast('(').substringAfterLast('.')
        val qualified = PsiTreeUtil.findChildrenOfType(myFixture.file, Qualified::class.java)
            .single { it.functionName() == functionName }

        assertEquals(call, expected, ReadAction.computeBlocking<String?, Throwable> { qualified.resolvedModuleName() })
    }

    private fun qualifiedCall(functionName: String): Call =
        PsiTreeUtil.findChildrenOfType(myFixture.file, Qualified::class.java).single { it.functionName() == functionName }

    /** The text of each call that Find Usages from [clause] finds, declarations left out. */
    private fun callSites(clause: Call): List<String> =
        findUsages(clause).filterNot { it.declaration }.map { usage ->
            ReadAction.computeBlocking<String, Throwable> {
                PsiTreeUtil.getParentOfType(usage.file.findElementAt(usage.range.startOffset), Call::class.java, false)
                    ?.let { generateSequence(it) { c -> c.parent as? Call }.last().text }
                    ?: usage.file.text.substring(usage.range.startOffset, usage.range.endOffset)
            }
        }

    private fun findUsages(clause: Call): List<PsiUsage> {
        val file = clause.containingFile
        val offset = CallDefinitionClause.nameIdentifier(clause)!!.textRange.startOffset
        val allOptions = AllSearchOptions(UsageOptions.createOptions(GlobalSearchScope.allScope(project)), textSearch = false)

        return ApplicationManager.getApplication().executeOnPooledThread(Callable<List<PsiUsage>> {
            ReadAction.nonBlocking(Callable<List<PsiUsage>> {
                val targets = searchTargets(file, offset)
                assertTrue("no search target at $offset", targets.isNotEmpty())
                targets.flatMap { buildQuery(project, it, allOptions).findAll() }.filterIsInstance<PsiUsage>()
            }).executeSynchronously()
        }).get()
    }
}
