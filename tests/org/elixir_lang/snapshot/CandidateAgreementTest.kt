package org.elixir_lang.snapshot

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.PsiReferenceService
import com.intellij.psi.PsiReferenceService.Hints.NO_HINTS
import org.elixir_lang.declaration.Applicability
import org.elixir_lang.declaration.Feature
import org.elixir_lang.declaration.Found
import org.elixir_lang.declaration.Use
import org.elixir_lang.declaration.sourceFor
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.scope.VisitedElementSetResolveResult
import org.elixir_lang.reference.Resolver
import org.elixir_lang.reference.resolver.Callable

/**
 * How the candidate list at every use relates to `multiResolve`, in both `incompleteCode` modes:
 * 1. `multiResolve` is the walk narrowed by `Resolver.preferred` and expanded with the paths it crossed;
 * 2. every `multiResolve` target is a candidate, a path, a variable, a prefix match, or reached through a
 *    prefix-matched `defdelegate` head;
 * 3. every candidate is a `multiResolve` target, or was dropped by the valid or same-module rule of
 *    `Resolver.preferred`; the source rule never drops one, since the module entry already prefers source;
 * 4. the valid candidates are the valid targets that are neither paths nor variables, but for those dropped as in 3.
 */
class CandidateAgreementTest : SnapshotTestCase() {
    fun testCallableDeclaration() {
        assertAgreement("psi/callable_declaration")
    }

    fun testInputs() {
        assertAgreement("snapshot/inputs")
    }

    /** `two/2` beside `two/1`, so that the valid preference drops a candidate. */
    fun testPreferences() {
        val dropped = assertAgreement("declaration/candidate_source/agreement")

        assertTrue("prefer valid dropped a candidate: $dropped", PREFER_VALID in dropped)
    }

    /** @return the rules that dropped a candidate. */
    private fun assertAgreement(inputDirectory: String): Set<String> {
        val (_, files) = copyInputs(inputDirectory)
        val disagreements = mutableListOf<String>()
        val dropped = mutableSetOf<String>()
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
                            disagreements += disagreements(reference, incompleteCode, dropped).map { "$use: $it" }
                        }
                    }
                }
            })
        }

        assertTrue(uses > 0)
        assertEquals(emptyList<String>(), disagreements)

        return dropped
    }

    private fun disagreements(
        reference: org.elixir_lang.reference.Callable,
        incompleteCode: Boolean,
        dropped: MutableSet<String>
    ): List<String> {
        val call = reference.element
        val walk = Callable.walk(call, incompleteCode)
        val preferValid = if (incompleteCode) walk else Resolver.preferIsValidResult(walk)
        val sourceElements = Resolver.preferSource(preferValid.map { it.element }).toSet()
        val preferSource = preferValid.filter { it.element in sourceElements }
        val preferSameModule = Resolver.preferElementUnderSameModule(call, preferSource)
        val multi = reference.multiResolve(incompleteCode).toList()
        val found = Use.of(call)?.let { sourceFor(Feature.SYMBOL_REFERENCES).candidates(it, incompleteCode) }.orEmpty()
        val disagreements = mutableListOf<String>()

        if (preferSameModule != Resolver.preferred(call, incompleteCode, walk)) {
            disagreements += "the preferences applied one by one are not Resolver.preferred"
        }

        // 1
        if (Callable.expand(preferSameModule).map { it.element to it.isValidResult } !=
            multi.map { it.element to it.isValidResult }
        ) {
            disagreements += "multiResolve is not expand(preferred(walk))"
        }

        // 2
        for (target in multi.map { it.element!! }) {
            if (found.none { it.element == target } &&
                !isPath(target, preferSameModule) &&
                walk.none { it.element == target && (it.reach == null || unnamed(it)) }
            ) {
                disagreements += "multiResolve target ${target.text.lines().first()} is no candidate, path, variable or prefix match"
            }
        }

        // 3
        val multiElements = multi.map { it.element }.toSet()
        val droppedFound = mutableSetOf<Found>()

        for (candidate in found.filter { it.element !in multiElements }) {
            val rule = when {
                preferValid.none { it.element == candidate.element } -> PREFER_VALID
                preferSource.none { it.element == candidate.element } -> PREFER_SOURCE
                preferSameModule.none { it.element == candidate.element } -> PREFER_SAME_MODULE
                else -> null
            }

            if (rule == null) {
                disagreements += "candidate ${candidate.element.text.lines().first()} is not in multiResolve and no rule dropped it"
            } else if (rule == PREFER_SOURCE) {
                disagreements += "candidate ${candidate.element.text.lines().first()} was dropped by the source stage, " +
                    "so the walk reached a compiled copy of a module its entry would have left out"
            } else {
                dropped += rule
                droppedFound += candidate
            }
        }

        // 4
        val validFound = found
            .filter { it.candidate.applicability == Applicability.VALID && it !in droppedFound }
            .map { it.element }
            .toSet()
        val validTargets = multi
            .filter { it.isValidResult }
            .map { it.element!! }
            .filter { target -> walk.any { it.element == target && it.reach != null } }
            .toSet()

        if (validFound != validTargets) {
            disagreements += "valid candidates ${validFound.map { it.text.lines().first() }} are not the valid targets " +
                "${validTargets.map { it.text.lines().first() }}"
        }

        return disagreements
    }

    /** Whether no declaration at [result] is named by the use: each is a prefix match, or behind a prefix-matched head. */
    private fun unnamed(result: VisitedElementSetResolveResult): Boolean =
        result.reached.isNotEmpty() &&
            result.reached.all { reached ->
                !reached.headNamed ||
                    reached.searchedAtom?.let { Applicability.of(reached.declaration, it, reached.valid) } == null
            }

    private fun isPath(target: PsiElement, preferred: List<VisitedElementSetResolveResult>): Boolean =
        (target as? Call)?.let(Callable::isPath) == true &&
            preferred.any { target in it.visitedElementSet }

    private companion object {
        const val PREFER_VALID = "prefer valid"
        const val PREFER_SOURCE = "prefer source"
        const val PREFER_SAME_MODULE = "prefer same module"
    }
}
