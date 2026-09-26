package org.elixir_lang.psi.scope

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import org.elixir_lang.psi.Import

/** @property importFilter what the `import` [element] was reached through brings in; `null` when it came through none. */
class VisitedElementSetResolveResult(
    element: PsiElement,
    validResult: Boolean,
    val visitedElementSet: Set<PsiElement>,
    val reach: Reach = Reach.OWN,
    val importFilter: Import.Filter? = null
) : PsiElementResolveResult(element, validResult) {
    constructor(element: PsiElement) : this(element, true, emptySet())
}
