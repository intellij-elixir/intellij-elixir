package org.elixir_lang.psi.scope

import com.intellij.psi.PsiElement

class ResolveResultOrderedSet {
    fun add(element: PsiElement, name: String, validResult: Boolean, visitedElementSet: Set<PsiElement>, reach: Reach = Reach.OWN) {
        if (element !in psiElementSet) {
            psiElementSet.add(element)
            val visitedElementSetResolveResult = VisitedElementSetResolveResult(element, validResult, visitedElementSet, reach)
            val existingVisitedElementSetResolveResultList = visitedElementSetResolveResultListByName[name]

            if (existingVisitedElementSetResolveResultList != null) {
                existingVisitedElementSetResolveResultList.add(visitedElementSetResolveResult)
            } else {
                nameOrder.add(name)

                val visitedElementSetResolveResultList = mutableListOf(visitedElementSetResolveResult)
                visitedElementSetResolveResultListByName[name] = visitedElementSetResolveResultList
            }
        }
    }

    fun addAll(other: ResolveResultOrderedSet) {
        other.nameOrder.forEach { name ->
            other.visitedElementSetResolveResultListByName[name]!!.forEach { visitedElementSetResolveResult ->
                add(
                        visitedElementSetResolveResult.element,
                        name,
                        visitedElementSetResolveResult.isValidResult,
                        visitedElementSetResolveResult.visitedElementSet,
                        visitedElementSetResolveResult.reach
                )
            }
        }
    }

    fun keepProcessing(incompleteCode: Boolean): Boolean = incompleteCode || !hasValidResult()

    fun toList(): List<VisitedElementSetResolveResult> =
            nameOrder
                    .flatMap { name ->
                        visitedElementSetResolveResultListByName[name]!!
                    }

    private val psiElementSet = mutableSetOf<PsiElement>()
    private val visitedElementSetResolveResultListByName = mutableMapOf<String, MutableList<VisitedElementSetResolveResult>>()
    private val nameOrder = mutableListOf<String>()

    private fun hasValidResult() =
            visitedElementSetResolveResultListByName.values.any {
                it.any { it.isValidResult }
            }
}
