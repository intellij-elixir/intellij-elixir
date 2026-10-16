package org.elixir_lang.snapshot

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.PsiReferenceService
import com.intellij.psi.PsiReferenceService.Hints.NO_HINTS
import org.elixir_lang.declaration.Applicability
import org.elixir_lang.declaration.Feature
import org.elixir_lang.declaration.Use
import org.elixir_lang.declaration.isContract
import org.elixir_lang.declaration.preferred
import org.elixir_lang.declaration.sourceFor
import org.elixir_lang.reference.resolver.Callable

/**
 * How the candidate list at every use relates to `multiResolve`, in both `incompleteCode` modes:
 * 1. every `multiResolve` target is a variable or a declaration the preference step keeps, found or delegated to;
 * 2. every declaration the step keeps is a `multiResolve` target, so a candidate is missing only where the step
 *    dropped it;
 * 3. a declaration target is valid exactly when one of the candidates the step keeps at it is valid and no
 *    contract.
 */
class CandidateAgreementTest : SnapshotTestCase() {
    fun testCallableDeclaration() {
        assertAgreement("psi/callable_declaration")
    }

    fun testInputs() {
        assertAgreement("snapshot/inputs")
    }

    /**
     * `two/2` beside `two/1`, and `snoc/2` beside a variable `snoc`, so that the step drops a candidate; and `f/1` beside
     * its `@callback`, a kept candidate that is never valid.
     */
    fun testPreferences() {
        val (dropped, keptContracts) = assertAgreement("declaration/candidate_source/agreement")

        assertTrue("the step dropped a candidate: $dropped", "two" in dropped)
        assertTrue("the step kept a contract: $keptContracts", "f" in keptContracts)
        assertTrue("the step dropped a candidate a variable binds: $dropped", "snoc" in dropped)
    }

    /** The names of the candidates the step dropped, and of the contracts it kept. */
    private data class Seen(val dropped: Set<String>, val keptContracts: Set<String>)

    private fun assertAgreement(inputDirectory: String): Seen {
        val (_, files) = copyInputs(inputDirectory)
        val disagreements = mutableListOf<String>()
        val dropped = mutableSetOf<String>()
        val keptContracts = mutableSetOf<String>()
        var uses = 0

        for (file in files.flatMap { it.viewProvider.allFiles }) {
            file.accept(object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    super.visitElement(element)

                    for (reference in PsiReferenceService.getService().getReferences(element, NO_HINTS)) {
                        if (reference !is org.elixir_lang.reference.Callable) continue
                        uses++

                        for (incompleteCode in listOf(false, true)) {
                            val use = "${file.name}:${reference.element.textOffset} `${reference.element.text.lines().first()}` ic=$incompleteCode"
                            disagreements +=
                                disagreements(reference, incompleteCode, dropped, keptContracts).map { "$use: $it" }
                        }
                    }
                }
            })
        }

        assertTrue(uses > 0)
        assertEquals(emptyList<String>(), disagreements)

        return Seen(dropped, keptContracts)
    }

    private fun disagreements(
        reference: org.elixir_lang.reference.Callable,
        incompleteCode: Boolean,
        dropped: MutableSet<String>,
        keptContracts: MutableSet<String>
    ): List<String> {
        val call = reference.element
        val multi = reference.multiResolve(incompleteCode).toList()
        val answer = Callable.answer(call, incompleteCode)
        val variables = answer.variables.mapNotNull { it.element }.toSet()
        val use = Use.of(call)
        val found = use?.let { sourceFor(Feature.SYMBOL_REFERENCES).candidates(it, incompleteCode) }.orEmpty()
        val kept = use
            ?.let { preferred(it, found, incompleteCode, answer.bound).flatMap { p -> listOf(p.found) + p.admittedDelegatedTo } }
            .orEmpty()
        val keptElements = kept.map { it.element }.toSet()
        val disagreements = mutableListOf<String>()

        // 1
        for (target in multi.map { it.element!! }) {
            if (target !in variables && target !in keptElements) {
                disagreements += "multiResolve target ${target.text.lines().first()} is no variable and no declaration the step keeps"
            }
        }

        // 2
        val multiElements = multi.mapNotNull { it.element }.toSet()

        for (element in keptElements - multiElements) {
            disagreements += "the step keeps ${element.text.lines().first()}, which is not in multiResolve"
        }

        found.filter { it.element !in keptElements }.mapTo(dropped) { it.candidate.declaration.name }
        kept.filter { isContract(it) }.mapTo(keptContracts) { it.candidate.declaration.name }

        // 3
        for (result in multi.filter { it.element in keptElements && it.element !in variables }) {
            val valid = kept.any {
                it.element == result.element && it.candidate.applicability == Applicability.VALID && !isContract(it)
            }

            if (result.isValidResult != valid) {
                disagreements += "${result.element!!.text.lines().first()} is valid=${result.isValidResult}, " +
                    "but its kept candidates say valid=$valid"
            }
        }

        return disagreements
    }
}
