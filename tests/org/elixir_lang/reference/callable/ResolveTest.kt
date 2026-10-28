package org.elixir_lang.reference.callable

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReference
import com.intellij.psi.impl.source.resolve.reference.impl.PsiMultiReference
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.documentation.quickDocumentationAtCaret
import org.elixir_lang.reference.CallDefinitionClause
import org.elixir_lang.reference.Callable
import org.elixir_lang.reference.CaptureNameArity

/**
 * What a call's, a capture's and a `@spec` head's reference resolves to: the first valid of its `multiResolve(false)`,
 * else nothing. Find Usages reads it through `TargetElementUtil.getTargetCandidates`, offering the reference's elements
 * beside the Symbol's search target unless a declaration the Symbol owns is among them.
 */
class ResolveTest : PlatformTestCase() {
    /** The wrong-arity `def` is a candidate, never the target, and Find Usages offers the Symbol alone. */
    fun testALoneWrongArityDefinitionIsNoTarget() {
        myFixture.configureByText("m.ex", "defmodule M do\n  def f(a), do: a\n\n  def run, do: <caret>f()\nend\n")

        assertResolves<Callable>(null, listOf("m.ex:2: def f(a), do: a (false)"))
    }

    fun testSeveralValidClausesGiveTheFirst() {
        myFixture.configureByText(
            "m.ex",
            "defmodule M do\n  def f(1), do: 1\n  def f(n), do: n\n\n  def run, do: <caret>f(2)\nend\n"
        )

        assertResolves<Callable>("m.ex:2: def f(1), do: 1", listOf("m.ex:2: def f(1), do: 1 (true)", "m.ex:3: def f(n), do: n (true)"))
    }

    /** `@args` gives the EEx function no known arity, so the call's candidate is `OPAQUE`. */
    fun testALoneOpaqueCandidateIsTheTarget() {
        myFixture.addFileToProject(
            "eex.ex",
            "defmodule EEx do\n  defmacro function_from_string(kind, name, source, args \\\\ [], options \\\\ []) do\n" +
                "    {kind, name, source, args, options}\n  end\nend\n"
        )
        myFixture.configureByText(
            "m.ex",
            "defmodule M do\n  require EEx\n  @args [:a]\n  EEx.function_from_string(:def, :render, \"<%= a %>\", @args)\n\n" +
                "  def run, do: <caret>render(1)\nend\n"
        )

        val eex = "m.ex:4: EEx.function_from_string(:def, :render, \"<%= a %>\", @args)"

        assertResolves<Callable>(eex, listOf("$eex (true)"))
    }

    /** Bindings with a literal `|` tail are counted through it. */
    fun testAnEExFunctionWhoseBindingsHaveATailIsTheTarget() {
        myFixture.addFileToProject(
            "eex.ex",
            "defmodule EEx do\n  defmacro function_from_string(kind, name, source, args \\\\ [], options \\\\ []) do\n" +
                "    {kind, name, source, args, options}\n  end\nend\n"
        )
        myFixture.configureByText(
            "m.ex",
            "defmodule M do\n  require EEx\n  EEx.function_from_string(:def, :render, \"<%= a %>\", [:a | [:b]])\n\n" +
                "  def run, do: <caret>render(1, 2)\nend\n"
        )

        val eex = "m.ex:3: EEx.function_from_string(:def, :render, \"<%= a %>\", [:a | [:b]])"

        assertResolves<Callable>(eex, listOf("$eex (true)"))
    }

    fun testAnEExFunctionWhoseBindingsHaveATailIsNoTargetBelowTheirCount() {
        myFixture.addFileToProject(
            "eex.ex",
            "defmodule EEx do\n  defmacro function_from_string(kind, name, source, args \\\\ [], options \\\\ []) do\n" +
                "    {kind, name, source, args, options}\n  end\nend\n"
        )
        myFixture.configureByText(
            "m.ex",
            "defmodule M do\n  require EEx\n  EEx.function_from_string(:def, :render, \"<%= a %>\", [:a | [:b]])\n\n" +
                "  def run, do: <caret>render(1)\nend\n"
        )

        val eex = "m.ex:3: EEx.function_from_string(:def, :render, \"<%= a %>\", [:a | [:b]])"

        assertResolves<Callable>(null, listOf("$eex (false)"))
    }

    /** An EEx function of unknown arity does not end the search, so an imported function of the call's arity is found. */
    fun testAnEExFunctionOfUnknownArityLeavesAnImportedFunctionTheTarget() {
        myFixture.addFileToProject(
            "eex.ex",
            "defmodule EEx do\n  defmacro function_from_string(kind, name, source, args \\\\ [], options \\\\ []) do\n" +
                "    {kind, name, source, args, options}\n  end\nend\n"
        )
        myFixture.addFileToProject("kernel.ex", "defmodule Kernel do\n  def length(list), do: list\nend\n")
        myFixture.configureByText(
            "m.ex",
            "defmodule M do\n  require EEx\n  @args [:a, :b]\n" +
                "  EEx.function_from_string(:def, :length, \"<%= a %>\", @args)\n\n  def run, do: <caret>length([])\nend\n"
        )

        val eex = "m.ex:4: EEx.function_from_string(:def, :length, \"<%= a %>\", @args)"
        val imported = "kernel.ex:2: def length(list), do: list"

        assertResolves<Callable>(imported, listOf("$eex (true)", "$imported (true)"))
    }

    /** `embed_template` defines `page_template/1` only. */
    fun testAZeroArityCallOfAnEmbeddedTemplateIsNoTarget() {
        embedTemplate("page_template()")

        assertResolves<Callable>(null, listOf("$EMBED_TEMPLATE (false)"))
    }

    fun testAOneArityCallOfAnEmbeddedTemplateIsTheTarget() {
        embedTemplate("page_template(a: 1)")

        assertResolves<Callable>(EMBED_TEMPLATE, listOf("$EMBED_TEMPLATE (true)"))
    }

    /** A name `unquote` gives is unknown, so every function of the module is an `OPAQUE` candidate, and none the target. */
    fun testAQualifiedUnquoteOfSeveralFunctionsIsNoTarget() {
        myFixture.addFileToProject("m.ex", "defmodule M do\n  def f(a), do: a\n  def g(a), do: a\nend\n")
        myFixture.configureByText(
            "use.ex",
            "defmodule U do\n  defmacro m(name) do\n    quote do\n      M.<caret>unquote(name)(1)\n    end\n  end\nend\n"
        )

        assertResolves<Callable>(null, listOf("m.ex:2: def f(a), do: a (true)", "m.ex:3: def g(a), do: a (true)"))
    }

    fun testAQualifiedUnquoteOfALoneFunctionIsTheTarget() {
        myFixture.addFileToProject("m.ex", "defmodule M do\n  def f(a), do: a\nend\n")
        myFixture.configureByText(
            "use.ex",
            "defmodule U do\n  defmacro m(name) do\n    quote do\n      M.<caret>unquote(name)(1)\n    end\n  end\nend\n"
        )

        assertResolves<Callable>("m.ex:2: def f(a), do: a", listOf("m.ex:2: def f(a), do: a (true)"))
    }

    fun testACallOfADelegationResolvesToTheDelegation() {
        delegation()
        myFixture.configureByText("use.ex", "defmodule U do\n  def run, do: M.<caret>x(1)\nend\n")

        assertResolves<Callable>(DELEGATION, listOf("$DELEGATION (true)", "$TARGET (true)"))
    }

    /** The `import` the use reaches the delegation through is how it was reached, not something it names. */
    fun testAnImportedDelegationResolvesToTheDelegationWithoutTheImport() {
        delegation()
        myFixture.configureByText("use.ex", "defmodule U do\n  import M\n\n  def run, do: <caret>x(1)\nend\n")

        assertResolves<Callable>(DELEGATION, listOf("$DELEGATION (true)", "$TARGET (true)"))
    }

    /** A name a variable binds is the variable, so Quick Documentation documents nothing. */
    fun testABoundNameIsOnlyItsVariable() {
        myFixture.configureByText(
            "use.ex",
            "defmodule U do\n  def snoc(q, x), do: {q, x}\n\n  def run(a, b), do: (fn snoc -> <caret>snoc end).({a, b})\nend\n"
        )

        assertResolves<Callable>(
            "use.ex:4: def run(a, b), do: (fn snoc -> snoc end).({a, b})",
            listOf("use.ex:4: def run(a, b), do: (fn snoc -> snoc end).({a, b}) (true)")
        )
        assertNull(myFixture.quickDocumentationAtCaret(project))
    }

    fun testACaptureOfSeveralValidClausesGivesTheFirst() {
        myFixture.configureByText(
            "m.ex",
            "defmodule M do\n  def f(1), do: 1\n  def f(n), do: n\n\n  def run, do: &<caret>f/1\nend\n"
        )

        assertResolves<CaptureNameArity>(
            "m.ex:2: def f(1), do: 1",
            listOf("m.ex:2: def f(1), do: 1 (true)", "m.ex:3: def f(n), do: n (true)")
        )
    }

    fun testACaptureOfADelegationResolvesToTheDelegation() {
        delegation()
        myFixture.configureByText("use.ex", "defmodule U do\n  def run, do: &M.<caret>x/1\nend\n")

        assertResolves<CaptureNameArity>(DELEGATION, listOf("$DELEGATION (true)", "$TARGET (true)"))
    }

    /** The wrong-arity `def` is a candidate, never the target, and Find Usages offers the Symbol alone. */
    fun testACaptureAtAWrongArityIsNoTarget() {
        myFixture.configureByText("m.ex", "defmodule M do\n  def f(a), do: a\n\n  def run, do: &<caret>f/2\nend\n")

        assertResolves<CaptureNameArity>(null, listOf("m.ex:2: def f(a), do: a (false)"))
    }

    fun testASpecOfSeveralClausesGivesTheFirst() {
        myFixture.configureByText(
            "m.ex",
            "defmodule M do\n  @spec <caret>f(term) :: term\n  def f(1), do: 1\n  def f(n), do: n\nend\n"
        )

        assertResolves<CallDefinitionClause>(
            "m.ex:3: def f(1), do: 1",
            listOf("m.ex:3: def f(1), do: 1 (true)", "m.ex:4: def f(n), do: n (true)")
        )
    }

    fun testASpecOfADelegationResolvesToTheDelegation() {
        myFixture.addFileToProject("t.ex", "defmodule T do\n  def x(a), do: a\nend\n")
        myFixture.configureByText("m.ex", "defmodule M do\n  @spec <caret>x(term) :: term\n  defdelegate x(a), to: T\nend\n")

        assertResolves<CallDefinitionClause>("m.ex:3: defdelegate x(a), to: T", listOf("m.ex:3: defdelegate x(a), to: T (true)"))
    }

    /** A `@callback` is a contract, not a body: a candidate, but never the target, of a call or a `@spec`. */
    fun testACallBesideACallbackOfItsNameResolvesToTheDefinition() {
        myFixture.configureByText(
            "m.ex",
            "defmodule M do\n  @callback f(term) :: term\n  def f(a), do: a\n\n  def run, do: <caret>f(1)\nend\n"
        )

        assertResolves<Callable>(
            "m.ex:3: def f(a), do: a",
            listOf("m.ex:2: @callback f(term) :: term (false)", "m.ex:3: def f(a), do: a (true)")
        )
    }

    fun testASpecBesideACallbackOfItsNameResolvesToTheDefinition() {
        myFixture.configureByText(
            "m.ex",
            "defmodule M do\n  @callback f(term) :: term\n  @spec <caret>f(term) :: term\n  def f(a), do: a\nend\n"
        )

        assertResolves<CallDefinitionClause>(
            "m.ex:4: def f(a), do: a",
            listOf("m.ex:2: @callback f(term) :: term (false)", "m.ex:4: def f(a), do: a (true)")
        )
    }

    fun testASpecWithOnlyACallbackOfItsNameIsNoTarget() {
        myFixture.configureByText("m.ex", "defmodule M do\n  @callback f(term) :: term\n  @spec <caret>f(term) :: term\nend\n")

        assertResolves<CallDefinitionClause>(null, listOf("m.ex:2: @callback f(term) :: term (false)"))
    }

    /**
     * The variable is the target: a function of the name beside it is a candidate, but never what the use names. Find
     * Usages offers no second target: a guard of `TargetElementEvaluator`'s `VariableSymbol.isHead` arm.
     */
    fun testAVariableBesideAFunctionOfItsNameIsTheTarget() {
        myFixture.configureByText("m.ex", "defmodule M do\n  def f, do: 1\n\n  def run(f), do: <caret>f\nend\n")

        assertResolves<Callable>("m.ex:4: def run(f), do: f", listOf("m.ex:4: def run(f), do: f (true)", "m.ex:2: def f, do: 1 (true)"))
    }

    private fun delegation() {
        myFixture.addFileToProject("t.ex", "defmodule T do\n  def x(a), do: a\nend\n")
        myFixture.addFileToProject("m.ex", "defmodule M do\n  defdelegate x(a), to: T\nend\n")
    }

    private fun embedTemplate(call: String) {
        myFixture.addFileToProject(
            "mix_generator.ex",
            "defmodule Mix.Generator do\n  defmacro embed_template(name, contents), do: {name, contents}\nend\n"
        )
        myFixture.configureByText(
            "m.ex",
            "defmodule M do\n  require Mix.Generator\n\n  Mix.Generator.embed_template(:page, \"Hi\")\n\n" +
                "  def run, do: <caret>$call\nend\n"
        )
    }

    private inline fun <reified T : PsiPolyVariantReference> assertResolves(resolved: String?, results: List<String>) {
        readAction {
            val reference = references().filterIsInstance<T>().single()

            assertEquals(
                "multiResolve(false)",
                results,
                reference.multiResolve(false).map { "${describe(it.element!!)} (${it.isValidResult})" }
            )
            assertEquals("resolve()", resolved, reference.resolve()?.let(::describe))
        }
        assertEquals("Find Usages' second targets", emptyList<String>(), secondTargets())
    }

    private fun references(): List<PsiReference> =
        when (val reference = myFixture.file.findReferenceAt(myFixture.caretOffset)) {
            is PsiMultiReference -> reference.references.toList()
            null -> emptyList()
            else -> listOf(reference)
        }

    /** What Find Usages offers beside the Symbol's search targets: the PSI elements the platform reads off the reference. */
    private fun secondTargets(): List<String> =
        readAction {
            TargetElementUtil.getInstance()
                .getTargetCandidates(TargetElementUtil.findReference(myFixture.editor, myFixture.caretOffset)!!)
                .map(::describe)
        }

    private fun describe(element: PsiElement): String {
        val file = element.containingFile
        val document = PsiDocumentManager.getInstance(project).getDocument(file)!!
        val line = document.getLineNumber(element.textRange.startOffset)
        val text = document.getText(
            com.intellij.openapi.util.TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line))
        )

        return "${file.name}:${line + 1}: ${text.trim()}"
    }

    private fun <T> readAction(block: () -> T): T = ReadAction.computeBlocking<T, Throwable> { block() }

    private companion object {
        const val DELEGATION = "m.ex:2: defdelegate x(a), to: T"
        const val EMBED_TEMPLATE = "m.ex:4: Mix.Generator.embed_template(:page, \"Hi\")"
        const val TARGET = "t.ex:2: def x(a), do: a"
    }
}
