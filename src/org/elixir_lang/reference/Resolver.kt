package org.elixir_lang.reference

import com.intellij.openapi.module.Module as IdeModule
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveResult
import org.elixir_lang.navigation.isDecompiled
import org.elixir_lang.psi.DelegationPrecedence
import com.intellij.util.concurrency.annotations.RequiresReadLock

object Resolver {
    fun <T : ResolveResult> preferred(
        element: PsiElement,
        incompleteCode: Boolean,
        resolveResultList: List<T>
    ): List<T> {
        val validResolveResultList = preferIsValidResult(incompleteCode, resolveResultList)
        val sourcePreferredList = preferSourceElement(validResolveResultList)
        val sameModuleResolveResultList = preferElementUnderSameModule(element, sourcePreferredList)

        return sameModuleResolveResultList
    }

    /**
     * What a reference at [element] resolves to: the first [preferred] valid result, or nothing. [preferred] keeps invalid
     * results when none is valid, as candidates that explain why a use does not compile; a candidate is not a target.
     * Where several are valid, a delegation the use names comes before what it delegates to.
     */
    fun <T : ResolveResult> resolved(element: PsiElement, resolveResultList: List<T>): PsiElement? =
        DelegationPrecedence
            .namedFirst(preferred(element, false, resolveResultList.filter(ResolveResult::isValidResult)), ResolveResult::getElement)
            .firstOrNull()
            ?.element

    /**
     * Applies the same source-over-decompiled and same-module preference logic as [preferred],
     * but operates on a raw list of [PsiElement]s rather than [ResolveResult]s.
     */
    fun <T : PsiElement> preferredElements(elementInModule: PsiElement, elementList: List<T>): List<T> {
        val sourcePreferredList = preferSource(elementList)
        return preferUnderSameModule(elementInModule, sourcePreferredList)
    }

    private fun <T : ResolveResult> preferIsValidResult(
        incompleteCode: Boolean,
        resolveResultList: List<T>
    ): List<T> = if (incompleteCode) {
        resolveResultList
    } else {
        preferIsValidResult(resolveResultList)
    }

    /** One result for each [key], in order of first appearance: a valid one where any of them is, else the first. */
    fun <T : ResolveResult, K> onePerKeyPreferringValid(resolveResultList: List<T>, key: (T) -> K): List<T> =
        resolveResultList.groupBy(key).values.map { same -> same.firstOrNull { it.isValidResult } ?: same.first() }

    fun <T : ResolveResult> preferIsValidResult(resolveResultList: List<T>): List<T> =
        filterIsValidResult(resolveResultList).takeIf(List<T>::isNotEmpty) ?: resolveResultList

    fun <T : ResolveResult> preferElementUnderSameModule(element: PsiElement, resolveResultList: List<T>): List<T> =
        preferUnderSameModule(element, resolveResultList, ResolveResult::getElement)

    fun <T : PsiElement> preferUnderSameModule(elementInModule: PsiElement, elementList: List<T>): List<T> =
        preferUnderSameModule(elementInModule, elementList) { it }

    private fun <T, U : PsiElement> preferUnderSameModule(
        elementInModule: PsiElement,
        list: List<T>,
        listElementToPsiElement: (listElement: T) -> U?
    ): List<T> =
        preferFiltered(list, listElementToPsiElement) {
            filterUnderSameModule(elementInModule, it, listElementToPsiElement)
        }

    private fun <T : ResolveResult> preferSourceElement(resolveResultList: List<T>): List<T> =
        preferSource(resolveResultList, ResolveResult::getElement)

    fun <T : PsiElement> preferSource(elementList: List<T>): List<T> =
        preferSource(elementList) { it }

    private fun <T, U : PsiElement> preferSource(
        list: List<T>,
        listElementToPsiElement: (listElement: T) -> U?
    ): List<T> =
        preferFiltered(list, listElementToPsiElement) { filterSource(it, listElementToPsiElement) }

    /**
     * Narrows [list] with [filter], keeping the whole list when the narrowed one is empty *or* holds
     * nothing but `defdelegate` heads.
     *
     * A head names a function without defining one, so it must not stand in for the target it points
     * at - which is what both preferences would otherwise keep when the target is decompiled or lives
     * outside the calling module.
     */
    private fun <T, U : PsiElement> preferFiltered(
        list: List<T>,
        listElementToPsiElement: (listElement: T) -> U?,
        filter: (List<T>) -> List<T>
    ): List<T> =
        filter(list)
            .takeIf { filtered ->
                DelegationPrecedence.standsIn(filtered, listElementToPsiElement)
            }
            ?: list

    private fun <T : ResolveResult> filterIsValidResult(resolveResultList: List<T>): List<T> =
        resolveResultList.filter(ResolveResult::isValidResult)

    private fun <T, U : PsiElement> filterUnderSameModule(
        elementInModule: PsiElement,
        list: List<T>,
        listElementToPsiElement: (listElement: T) -> U?
    ): List<T> = if (list.isNotEmpty()) {
        moduleForSourceOrLibrary(elementInModule)
            ?.let { module -> filterUnderModule(module, list, listElementToPsiElement) }
            ?: emptyList()
    } else {
        list
    }

    // `ModuleUtil.findModuleForPsiElement` returns `null` for library source, so need to find module a different way if a library
    private fun moduleForSourceOrLibrary(element: PsiElement): IdeModule? =
        ModuleUtil.findModuleForPsiElement(element) ?: element
            .containingFile
            .virtualFile
            ?.takeIf { ProjectFileIndex.getInstance(element.project).isInLibrary(it) }
            ?.let { virtualFile ->
                moduleForSourceOrLibrary(element, virtualFile)
            }

    private fun moduleForSourceOrLibrary(
        element: PsiElement,
        virtualFile: VirtualFile
    ): IdeModule? =
        ModuleManager
            .getInstance(element.project)
            .modules
            .asSequence()
            .mapNotNull { module ->
                moduleDepth(virtualFile, module)
            }
            .minByOrNull { (_, depth) -> depth }
            ?.first

    private fun moduleDepth(virtualFile: VirtualFile, module: IdeModule): Pair<IdeModule, Int>? {
        val contentRootSet = ModuleRootManager.getInstance(module).contentRoots.toSet()

        return depth(virtualFile, contentRootSet)
            ?.let { depth -> module to depth }
    }

    private fun depth(virtualFile: VirtualFile, roots: Set<VirtualFile>?): Int? =
        if (!roots.isNullOrEmpty()) {
            var ancestor: VirtualFile? = virtualFile
            var finalDepth: Int? = null
            var currentDepth = 0

            while (ancestor != null) {
                if (roots.contains(ancestor)) {
                    finalDepth = currentDepth
                    break
                }

                ancestor = ancestor.parent
                currentDepth++
            }

            finalDepth
        } else {
            null
        }

    private fun <T, U : PsiElement> filterUnderModule(
        module: IdeModule,
        list: List<T>,
        listElementToPsiElement: (listElement: T) -> U?
    ): List<T> = if (list.isNotEmpty()) {
        val contentRootSet = ModuleRootManager.getInstance(module).contentRoots.toSet()
        list.filter { listElement ->
            listElementToPsiElement(listElement)
                ?.let { isUnder(it, contentRootSet) }
                ?: false
        }
    } else {
        emptyList()
    }

    private fun <T : PsiElement, V : VirtualFile> isUnder(element: T, roots: Set<V>): Boolean =
        element.containingFile?.virtualFile?.let { VfsUtilCore.isUnder(it, roots) } ?: false

    private fun <T, U : PsiElement> filterSource(
        list: List<T>,
        listElementToPsiElement: (listElement: T) -> U?
    ): List<T> =
        list.filter { listElement ->
            listElementToPsiElement(listElement)
                ?.let { !it.isDecompiled() }
                ?: false
        }
}
