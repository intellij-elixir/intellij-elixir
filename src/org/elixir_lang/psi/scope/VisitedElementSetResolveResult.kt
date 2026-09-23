package org.elixir_lang.psi.scope

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult

class VisitedElementSetResolveResult(
    element: PsiElement,
    validResult: Boolean,
    val visitedElementSet: Set<PsiElement>,
    val reach: Reach = Reach.OWN
) : PsiElementResolveResult(element, validResult) {
    constructor(element: PsiElement) : this(element, true, emptySet())
}
