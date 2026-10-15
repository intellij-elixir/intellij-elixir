package org.elixir_lang.declaration

import com.intellij.model.psi.PsiSymbolReferenceService
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.resolve.reference.impl.PsiMultiReference
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.model.psi.atom.AtomReference
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.reference.CaptureNameArity
import org.elixir_lang.structure_view.element.CallDefinitionSpecification

/**
 * The `Use` of a capture, a `@spec` head and an MFA atom, and the candidates the source gives it, each as
 * `file:line: text of that line (applicability)`.
 */
class UseFactoryTest : PlatformTestCase() {
    fun testACaptureIsUsedAtTheCapturesArity() {
        myFixture.configureByText(
            "capture.ex",
            """
            defmodule M do
              def f(), do: 0
              def f(a, b), do: a + b

              def run, do: &<caret>f/2
            end
            """.trimIndent()
        )

        val reference = captureReference()
        val use = Use.capture(reference.nameElement, reference.arity) as Use.Named

        assertEquals(2, use.arity)
        assertEquals(
            listOf("capture.ex:2: def f(), do: 0 (WRONG_ARITY)", "capture.ex:3: def f(a, b), do: a + b (VALID)"),
            candidates(use)
        )
    }

    fun testAQualifiedCaptureIsUsedAtTheCapturesArity() {
        myFixture.addFileToProject(
            "m.ex",
            """
            defmodule M do
              def f(), do: 0
              def f(a, b), do: a + b
            end
            """.trimIndent()
        )
        myFixture.configureByText("capture.ex", "defmodule C do\n  def run, do: &M.<caret>f/2\nend\n")

        val reference = captureReference()
        val use = Use.capture(reference.nameElement, reference.arity) as Use.Named

        assertEquals(2, use.arity)
        assertEquals(
            listOf("m.ex:2: def f(), do: 0 (WRONG_ARITY)", "m.ex:3: def f(a, b), do: a + b (VALID)"),
            candidates(use)
        )
    }

    /** An `import`'s `only:` admits by arity, so the walk runs at the capture's. */
    fun testAnImportedCaptureIsAdmittedAtTheCapturesArity() {
        myFixture.addFileToProject("m.ex", importedFunctions)
        myFixture.configureByText("capture.ex", importingCapture)

        val reference = captureReference()

        assertEquals(
            listOf("m.ex:3: def f(a, b), do: a + b (VALID)"),
            candidates(Use.capture(reference.nameElement, reference.arity)!!)
        )
    }

    /** The name call is walked at its own arity too, and neither walk answers for the other. */
    fun testACapturesWalkIsNotItsNameCallsWalk() {
        myFixture.addFileToProject("m.ex", importedFunctions)
        myFixture.configureByText("capture.ex", importingCapture)

        val reference = captureReference()
        val atZero = listOf("m.ex:3: def f(a, b), do: a + b (WRONG_ARITY)")
        val atTwo = listOf("m.ex:3: def f(a, b), do: a + b (VALID)")

        assertEquals(atZero, candidates(Use.of(reference.nameElement)!!))
        assertEquals(atTwo, candidates(Use.capture(reference.nameElement, reference.arity)!!))
        assertEquals(atZero, candidates(Use.of(reference.nameElement)!!))
    }

    fun testASpecNamesItsModulesDefinitionsWhereverTheyAre() {
        myFixture.configureByText(
            "spec.ex",
            """
            defmodule S do
              @spec ma<caret>p(t) :: t
              def map(x), do: x
              def map(x, y), do: {x, y}
            end
            """.trimIndent()
        )

        assertEquals(
            listOf("spec.ex:3: def map(x), do: x (VALID)", "spec.ex:4: def map(x, y), do: {x, y} (WRONG_ARITY)"),
            candidates(specification())
        )
    }

    fun testASpecDoesNotNameWhatItsModuleImports() {
        myFixture.addFileToProject("enum.ex", "defmodule Enum do\n  def map(e, f), do: f.(e)\nend\n")
        myFixture.configureByText(
            "spec.ex",
            """
            defmodule S do
              import Enum

              @spec ma<caret>p(t, f) :: t
              def other(x), do: x
            end
            """.trimIndent()
        )

        assertEquals(emptyList<String>(), candidates(specification()))
    }

    fun testASpecNamesAnUnquoteNamedFunction() {
        myFixture.configureByText(
            "spec.ex",
            """
            defmodule S do
              @spec unq<caret>uote(:in)(t) :: t
              def unquote(:in)(x), do: x
            end
            """.trimIndent()
        )

        assertEquals(listOf("spec.ex:3: def unquote(:in)(x), do: x (VALID)"), candidates(specification()))
    }

    fun testASpecNamesADelegation() {
        myFixture.addFileToProject("t.ex", "defmodule T do\n  def x(a), do: a\nend\n")
        myFixture.configureByText(
            "spec.ex",
            """
            defmodule S do
              @spec <caret>x(t) :: t
              defdelegate x(a), to: T
            end
            """.trimIndent()
        )

        assertEquals(listOf("spec.ex:3: defdelegate x(a), to: T (VALID)"), candidates(specification()))
    }

    fun testAnMfaAtomAtALiteralArityNamesThatArity() {
        myFixture.addFileToProject("m.ex", "defmodule M do\n  def f(a), do: a\n  def f(a, b), do: a + b\nend\n")
        myFixture.configureByText("mfa.ex", "defmodule U do\n  def run, do: apply(M, :<caret>f, [1])\nend\n")

        assertEquals(
            listOf("m.ex:2: def f(a), do: a (VALID)", "m.ex:3: def f(a, b), do: a + b (WRONG_ARITY)"),
            candidates(mfa())
        )
        assertEquals(mfaToday(), candidates(mfa()))
    }

    fun testAnMfaTupleAtALiteralArityNamesThatArity() {
        myFixture.addFileToProject("m.ex", "defmodule M do\n  def f(a), do: a\nend\n")
        myFixture.configureByText("mfa.ex", "defmodule U do\n  def run, do: {M, :<caret>f, 1}\nend\n")

        assertEquals(listOf("m.ex:2: def f(a), do: a (VALID)"), candidates(mfa()))
        assertEquals(mfaToday(), candidates(mfa()))
    }

    /** Elixir checks an MFA's arity only when it is a literal, so every arity may be the one called. */
    fun testAnMfaAtomAtANonLiteralArityNamesEveryArityUndecided() {
        myFixture.addFileToProject("m.ex", "defmodule M do\n  def f(a), do: a\n  def f(a, b), do: a + b\nend\n")
        myFixture.configureByText("mfa.ex", "defmodule U do\n  def run(args), do: apply(M, :<caret>f, args)\nend\n")

        val use = mfa() as Use.Remote

        assertNull(use.arity)
        assertEquals(
            listOf("m.ex:2: def f(a), do: a (OPAQUE)", "m.ex:3: def f(a, b), do: a + b (OPAQUE)"),
            candidates(use)
        )
    }

    fun testAnMfaAtomAtANonLiteralArityNamesADelegationAndItsTarget() {
        myFixture.addFileToProject("t.ex", "defmodule T do\n  def x(a), do: a\nend\n")
        myFixture.addFileToProject("m.ex", "defmodule M do\n  defdelegate x(a), to: T\nend\n")
        myFixture.configureByText("mfa.ex", "defmodule U do\n  def run(args), do: apply(M, :<caret>x, args)\nend\n")

        assertEquals(
            listOf("m.ex:2: defdelegate x(a), to: T (OPAQUE)", "t.ex:2: def x(a), do: a (OPAQUE)"),
            candidates(mfa())
        )
    }

    fun testAnMfaAtomAtANonLiteralArityReachesOnlyTheArityADelegationPasses() {
        myFixture.addFileToProject("t.ex", "defmodule T do\n  def x(a, b), do: {a, b}\n  def x(a), do: a\nend\n")
        myFixture.addFileToProject("m.ex", "defmodule M do\n  defdelegate x(a), to: T\nend\n")
        myFixture.configureByText("mfa.ex", "defmodule U do\n  def run(args), do: apply(M, :<caret>x, args)\nend\n")

        assertEquals(
            listOf(
                "m.ex:2: defdelegate x(a), to: T (OPAQUE)",
                "t.ex:2: def x(a, b), do: {a, b} (WRONG_ARITY)",
                "t.ex:3: def x(a), do: a (OPAQUE)"
            ),
            candidates(mfa())
        )
    }

    private val importedFunctions = "defmodule M do\n  def f(), do: 0\n  def f(a, b), do: a + b\nend\n"

    private val importingCapture = "defmodule C do\n  import M, only: [f: 2]\n\n  def run, do: &<caret>f/2\nend\n"

    private fun captureReference(): CaptureNameArity {
        val reference = myFixture.file.findReferenceAt(myFixture.caretOffset)
        val references = (reference as? PsiMultiReference)?.references?.toList() ?: listOfNotNull(reference)

        return references.filterIsInstance<CaptureNameArity>().single()
    }

    private fun moduleAttribute(): AtUnqualifiedNoParenthesesCall<*> =
        PsiTreeUtil.getParentOfType(
            myFixture.file.findElementAt(myFixture.caretOffset),
            AtUnqualifiedNoParenthesesCall::class.java
        )!!

    private fun specification(): Use {
        val moduleAttribute = moduleAttribute()
        val head = CallDefinitionSpecification.specification(moduleAttribute)
            ?.let { CallDefinitionSpecification.specificationType(it) }!!

        return Use.specification(head, moduleAttribute)!!
    }

    private fun atom(): ElixirAtom =
        PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(myFixture.caretOffset), ElixirAtom::class.java)!!

    private fun mfa(): Use = Use.mfa(atom())!!

    /** The atom reference's results, a valid one as `VALID` and any other as `WRONG_ARITY`. */
    @Suppress("UnstableApiUsage")
    private fun mfaToday(): List<String> =
        PsiSymbolReferenceService.getService().getReferences(atom())
            .filterIsInstance<AtomReference>()
            .single()
            .multiResolve(false)
            .map { "${describe(it.element!!)} (${if (it.isValidResult) "VALID" else "WRONG_ARITY"})" }
            .sorted()

    private fun candidates(use: Use): List<String> =
        sourceFor(Feature.SYMBOL_REFERENCES).candidates(use, false)
            .map { "${describe(it.element)} (${it.candidate.applicability})" }
            .sorted()

    /** [element] as `file:line: text of that line`. */
    private fun describe(element: PsiElement): String {
        val file = element.containingFile
        val text = file.text
        val offset = element.textOffset
        val line = text.substring(0, offset).count { it == '\n' } + 1
        val lineStart = text.lastIndexOf('\n', offset - 1) + 1
        val lineEnd = text.indexOf('\n', offset).let { if (it == -1) text.length else it }

        return "${file.name}:$line: ${text.substring(lineStart, lineEnd).trim()}"
    }
}
