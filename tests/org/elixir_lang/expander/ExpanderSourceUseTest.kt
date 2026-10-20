package org.elixir_lang.expander

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.testFramework.LightProjectDescriptor
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.declaration.ArityKnowledge
import org.elixir_lang.declaration.Definer
import org.elixir_lang.declaration.Found
import org.elixir_lang.declaration.Use
import org.elixir_lang.declaration.declaringModuleName
import org.elixir_lang.declaration.preferred
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.scope.WalkTestSupport
import org.elixir_lang.snapshot.LegElixirProjectDescriptor
import java.io.File

/** What [ExpanderSource] answers a use of a definition a macro's output injected with, as the walk answers it. */
class ExpanderSourceUseTest : PlatformTestCase() {
    override fun getProjectDescriptor(): LightProjectDescriptor = LegElixirProjectDescriptor

    /** A definition a `use` injects is reached through the `use`, at its `def` in the `quote`. */
    fun testAnInjectedDefinitionIsReachedThroughTheUse() {
        using("      def f, do: 1\n")
        val file = add("a.ex", "defmodule A do\n  use U\n  def g, do: f()\nend\n")

        assertEquals("A.f/0 USE VALID via [use U] b.ex line 4 `def f, do: 1`", candidates(file, "f()"))
    }

    /** A remote call reaches it through the `use` too, which is in the module's file, not the caller's. */
    fun testARemoteCallReachesAnInjectedDefinitionThroughTheUse() {
        using("      def f, do: 1\n")
        add("a.ex", "defmodule A do\n  use U\nend\n")
        val file = add("c.ex", "defmodule C do\n  def g, do: A.f()\nend\n")

        assertEquals("A.f/0 USE VALID via [use U] b.ex line 4 `def f, do: 1`", candidates(file, "A.f()"))
    }

    /**
     * An `import` the `use` in `Outer` injects reaches `Inner`'s call as `IMPORT`, through the `use`, and is declared by
     * the imported module.
     */
    fun testAnImportAUseInjectedIsReachedThroughTheUse() {
        add("y.ex", "defmodule Y do\n  def f, do: 1\nend\n")
        using("      import Y\n")
        val file = add("a.ex", "defmodule Outer do\n  use U\n\n  defmodule Inner do\n    def g, do: f()\n  end\nend\n")

        assertEquals("Y.f/0 IMPORT VALID via [use U] y.ex line 2 `def f, do: 1`", candidates(file, "f()"))
    }

    /** Each head of an injected definition is answered at its own `def`: a bodiless head and each clause. */
    fun testEveryHeadOfAnInjectedDefinitionIsAnswered() {
        using("      def pad(a, b \\\\ 1)\n      def pad(a, 0), do: a\n      def pad(a, b), do: {a, b}\n")
        val file = add("a.ex", "defmodule A do\n  use U\n  def g, do: pad(1)\nend\n")

        assertEquals(
            "A.pad/1..2 USE VALID via [use U] b.ex line 4 `def pad(a, b \\\\ 1)`\n" +
                "A.pad/1..2 USE VALID via [use U] b.ex line 5 `def pad(a, 0), do: a`\n" +
                "A.pad/1..2 USE VALID via [use U] b.ex line 6 `def pad(a, b), do: {a, b}`",
            candidates(file, "pad(1)"),
        )
    }

    /**
     * A `.beam`'s macro, read from its `Dbgi`, injects a definition answered at the macro's clause in the mirror: debug
     * info places nothing more finely than a clause.
     */
    fun testADebugInfoMacrosDefinitionIsAnsweredInTheBeam() {
        val file = add("a.ex", "defmodule A do\n  use Application\n  def g, do: stop(1)\nend\n")

        assertEquals(
            "A.stop/1 USE VALID via [use Application] Elixir.Application.beam.decompiled.ex line N `defmacro __using__(_) do`",
            candidates(file, "stop(1)").replace(Regex(" line \\d+ "), " line N "),
        )
    }

    /** The function a `.beam`'s macro injects is a function, though it is answered at the macro's clause. */
    fun testADebugInfoMacrosFunctionIsAFunction() {
        val file = add("a.ex", "defmodule A do\n  use Application\n  def g, do: stop(1)\nend\n")

        assertEquals(Definer.DEF.capabilities, found(file, "stop(1)").single().candidate.declaration.capabilities)
    }

    /** Each name a `.beam`'s macro injects is offered after the module's name, though all are at one macro clause. */
    fun testEveryNameADebugInfoMacroInjectsIsVisible() {
        val ebin = FileUtil.createTempDirectory("injecting_use_ebin", null, true)
        File(INJECTING_USE, "Elixir.InjectingUse.beam").copyTo(File(ebin, "Elixir.InjectingUse.beam"))
        add("a.ex", "defmodule A do\n  use InjectingUse\nend\n")
        val file = add("c.ex", "defmodule C do\n  def g, do: A.foo()\nend\n")

        WalkTestSupport.withLibrary(project, myFixture.module, testRootDisposable, "injecting_use", ebin) {
            assertEquals(listOf("bar", "foo"), ExpanderSource.visible(call(file, "A.foo()"), inputs).map { it.lookupName }.sorted())
        }
    }

    /** The module's own `def` overriding an injected one is `OWN`. */
    fun testAnOverridingDefinitionIsOwn() {
        using("      def f, do: 1\n      defoverridable f: 0\n")
        val file = add("a.ex", "defmodule A do\n  use U\n  def f, do: 2\n  def g, do: f()\nend\n")

        assertEquals("A.f/0 OWN VALID via [] a.ex line 3 `def f, do: 2`", candidates(file, "f()"))
    }

    /** A definition with an injected clause and the module's own is answered `USE` at the one and `OWN` at the other. */
    fun testMixedClausesAreAnsweredEachByItsOwnReach() {
        using("      def f(0), do: 0\n")
        val file = add("a.ex", "defmodule A do\n  use U\n  def f(n), do: n\n  def g, do: f(1)\nend\n")

        assertEquals(
            "A.f/1 USE VALID via [use U] b.ex line 4 `def f(0), do: 0`\n" +
                "A.f/1 OWN VALID via [] a.ex line 3 `def f(n), do: n`",
            candidates(file, "f(1)"),
        )
    }

    /** The module's own definition, and one the caller passes through a macro's output, stay `OWN` where written. */
    fun testTheWalksOwnAnswersStay() {
        add(
            "b.ex",
            "defmodule U do\n  defmacro wrap(do: block) do\n    quote do\n      def f, do: 1\n      unquote(block)\n" +
                "    end\n  end\nend\n",
        )
        val file = add(
            "a.ex",
            "defmodule A do\n  require U\n\n  U.wrap do\n    def h, do: 2\n  end\n\n  def g, do: {h(), k()}\n" +
                "  def k, do: 3\nend\n",
        )

        assertEquals("A.h/0 OWN VALID via [] a.ex line 5 `def h, do: 2`", candidates(file, "h()"))
        assertEquals("A.k/0 OWN VALID via [] a.ex line 9 `def k, do: 3`", candidates(file, "k()"))
    }

    /**
     * A chain of delegations the same macro injects into each module is followed to its end: `A`'s and `B`'s
     * `defdelegate` are both placed at `U`'s, but they are each module's own.
     */
    fun testADelegationChainOneMacroInjectsIsFollowedToItsEnd() {
        add(
            "b.ex",
            "defmodule U do\n  defmacro __using__(opts) do\n    quote do\n" +
                "      defdelegate f, to: unquote(Keyword.get(opts, :to))\n    end\n  end\nend\n",
        )
        add("a.ex", "defmodule A do\n  use U, to: B\nend\n")
        add("c.ex", "defmodule B do\n  use U, to: C\nend\n")
        add("d.ex", "defmodule C do\n  def f, do: 1\nend\n")
        val file = add("e.ex", "defmodule E do\n  def g, do: A.f()\nend\n")

        val delegate = "defdelegate f, to: unquote(Keyword.get(opts, :to))"

        assertEquals(
            "A.f/0 USE VALID via [use U, to: B] b.ex line 4 `$delegate`\n" +
                "B.f/0 DELEGATION_TARGET VALID via [use U, to: B | use U, to: C | $delegate] b.ex line 4 `$delegate`\n" +
                "C.f/0 DELEGATION_TARGET VALID via [use U, to: B | use U, to: C | $delegate | $delegate] d.ex line 2 " +
                "`def f, do: 1`",
            candidates(file, "A.f()"),
        )
    }

    /**
     * The delegates one `defdelegate` injects at two arities each go with the target of their own arity, so `A.f(1, 2)`
     * keeps `T.f/2`.
     */
    fun testEachDelegateOneDefdelegateInjectsIsPairedWithItsOwnTarget() {
        add("t.ex", "defmodule T do\n  def f(x), do: {:one, x}\n  def f(x, y), do: {:two, x, y}\nend\n")
        add(
            "b.ex",
            "defmodule U do\n  defmacro __using__(arity) do\n" +
                "    args = if arity == 1, do: [quote(do: x)], else: [quote(do: x), quote(do: y)]\n\n" +
                "    quote do\n      defdelegate f(unquote_splicing(args)), to: T\n    end\n  end\nend\n",
        )
        add("a.ex", "defmodule A do\n  use U, 1\n  use U, 2\nend\n")
        val file = add("c.ex", "defmodule B do\n  def k, do: A.f(1, 2)\nend\n")
        val use = Use.of(call(file, "A.f(1, 2)"))!!
        val delegate = "defdelegate f(unquote_splicing(args)), to: T"

        assertEquals(
            "A.f/2 USE VALID via [use U, 2] b.ex line 6 `$delegate`\n" +
                "  T.f/2 DELEGATION_TARGET VALID via [use U, 2 | $delegate] t.ex line 3 `def f(x, y), do: {:two, x, y}`",
            preferred(use, found(file, "A.f(1, 2)"), incompleteCode = false).joinToString("\n") { preferred ->
                (listOf(render(listOf(preferred.found))) + preferred.delegatedTo.map { "  " + render(listOf(it)) })
                    .joinToString("\n")
            },
        )
    }

    /** A `def` the caller wrote, which a macro walks with `Macro.prewalk/2`, is declared where the caller wrote it. */
    fun testACallersDefAMacroWalksIsDeclaredWhereTheCallerWroteIt() {
        add("deco.ex", "defmodule Deco do\n  defmacro deco(do: block), do: Macro.prewalk(block, & &1)\nend\n")
        add("a.ex", "defmodule A do\n  require Deco\n\n  Deco.deco do\n    def f, do: raise(\"x\")\n  end\nend\n")
        val file = add("c.ex", "defmodule C do\n  def g, do: A.f()\nend\n")

        assertEquals("A.f/0", "a.ex line 5 `def f, do: raise(\"x\")`", render(found(file, "A.f()")).substringAfter("] "))
    }

    /** The heads of the many definitions a `use` injects are cancelled as each is declared. */
    fun testTheHeadsAUseInjectsAreCancelledAsTheyAreDeclared() {
        add(
            "b.ex",
            "defmodule U do\n  defmacro __using__(_) do\n" +
                "    {:__block__, [], for(n <- [${(1..INJECTED).joinToString()}], do: quote(do: def(unquote(:\"f#{n}\")(), do: 1)))}\n  end\nend\n",
        )
        add("a.ex", "defmodule A do\n  use U\nend\n")
        val call = call(add("c.ex", "defmodule C do\n  def g, do: A.f1()\nend\n"), "A.f1()")

        assertCancelledIn("org.elixir_lang.expander.ExpanderSource", "defined", INJECTED / 2) { ExpanderSource.visible(call, inputs) }
    }

    /** `b.ex`, `U`, whose `__using__` quotes [body]. */
    private fun using(body: String) {
        add("b.ex", "defmodule U do\n  defmacro __using__(_) do\n    quote do\n$body    end\n  end\nend\n")
    }

    private fun add(name: String, code: String): PsiFile = myFixture.addFileToProject(name, code)

    /** The candidates of the use that is the only call [call] in [file], one a line. */
    private fun candidates(file: PsiFile, call: String): String = render(found(file, call))

    /** The candidates of the use that is the only call [call] in [file]. */
    private fun found(file: PsiFile, call: String): List<Found> = ExpanderSource.candidates(Use.of(call(file, call))!!, inputs)

    /** The only call [call] in [file]. */
    private fun call(file: PsiFile, call: String): Call {
        val offset = file.text.indexOf(call)
        assertTrue("`$call` once in the file", offset >= 0 && offset == file.text.lastIndexOf(call))

        return generateSequence(file.findElementAt(offset)) { it.parent }
            .filterIsInstance<Call>()
            .first { it.textRange.startOffset == offset && it.text == call }
    }

    private val inputs get() = ProjectModules.Inputs(ElixirLanguageLevel.of("1.20.4"), legKernel)

    /**
     * [found] as `<declaring module>.<name>/<arity> <reach> <applicability> via [<calls>] <file> line <n> `<first
     * line>``, one a line.
     */
    private fun render(found: List<Found>): String =
        found.joinToString("\n") { found ->
            val declaration = found.candidate.declaration
            val via = found.via.joinToString(" | ") { it.text.lines().first() }

            "${found.declaringModuleName()}.${declaration.name}/${arity(declaration.arity)} ${found.candidate.reach} " +
                "${found.candidate.applicability} via [$via] ${found.element.containingFile.name} line " +
                "${lineOf(found.element)} `${found.element.text.lines().first()}`"
        }

    private fun lineOf(element: PsiElement): Int =
        StringUtil.offsetToLineNumber(element.containingFile.text, element.textRange.startOffset) + 1

    private fun arity(arity: ArityKnowledge): String =
        when (arity) {
            is ArityKnowledge.Exact -> "${arity.arity}"
            is ArityKnowledge.Range -> "${arity.minimum}..${arity.maximum}"
            is ArityKnowledge.Open -> "${arity.minimum}+"
            ArityKnowledge.Unknown -> "?"
        }

    private companion object {
        /** `InjectingUse`, whose `__using__` injects `foo/0` and `bar/0`, and its `.beam`. */
        val INJECTING_USE = File("testData/org/elixir_lang/expander/injecting_use")

        /** How many definitions a `use` injects, more than are written in one module. */
        const val INJECTED = 2_000
    }
}
