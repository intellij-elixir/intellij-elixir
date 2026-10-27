package org.elixir_lang.psi.scope.callable_table

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.PsiReferenceService
import com.intellij.psi.PsiReferenceService.Hints.NO_HINTS
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import org.elixir_lang.code_insight.completion.unqualifiedLookupElements
import org.elixir_lang.declaration.Use
import org.elixir_lang.psi.scope.CallableTable
import org.elixir_lang.psi.scope.VisitedElementSetResolveResult
import org.elixir_lang.psi.scope.call_definition_clause.LegacyWalkSource
import org.elixir_lang.psi.scope.call_definition_clause.Variants
import org.elixir_lang.reference.Callable
import org.elixir_lang.reference.resolver.Callable as CallableResolver

/**
 * Every answer the call walk gives for each `Callable` use under a root, in visitor order and never sorted: the walk
 * itself and `multiResolve` in both `incompleteCode` modes, the candidates, and completion's lookups and what it sees.
 */
class WalkDump(private val project: Project, private val root: VirtualFile) {
    /** [files], and what each injects when [injected], with the table on or off. */
    fun lines(fixture: CodeInsightTestFixture, files: List<VirtualFile>, table: Boolean, injected: Boolean = false): List<String> {
        val previous = CallableTable.enabled
        CallableTable.enabled = table
        // A file added is a PSI change, so every cached walk and table is recomputed for this run.
        fixture.addFileToProject("walk_dump_${counter++}.ex", "")

        return try {
            files.sortedBy { it.path }.flatMap { lines(PsiManager.getInstance(project).findFile(it)!!, injected) }
        } finally {
            CallableTable.enabled = previous
        }
    }

    private fun lines(file: PsiFile, injected: Boolean): List<String> {
        val lines = mutableListOf<String>()
        val pending = ArrayDeque(file.viewProvider.allFiles)

        while (pending.isNotEmpty()) {
            pending.removeFirst().accept(object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    super.visitElement(element)

                    if (injected && element is com.intellij.psi.PsiLanguageInjectionHost) {
                        com.intellij.lang.injection.InjectedLanguageManager.getInstance(project)
                            .enumerateEx(element, element.containingFile, false) { injectedPsi, _ ->
                                pending.addAll(injectedPsi.viewProvider.allFiles)
                            }
                    }

                    for (reference in PsiReferenceService.getService().getReferences(element, NO_HINTS)) {
                        if (reference is Callable) lines += lines(reference)
                    }
                }
            })
        }

        return lines
    }

    private fun lines(reference: Callable): List<String> {
        val call = reference.element
        val use = "${location(call)}  `${call.text.lineSequence().first().trim().take(60)}`"
        val lines = mutableListOf<String>()

        for (incompleteCode in listOf(false, true)) {
            guarded(lines, "$use  incompleteCode=$incompleteCode") {
                lines += "$use  incompleteCode=$incompleteCode  walk  " +
                    CallableResolver.walk(call, incompleteCode).joinToString(" | ", transform = ::describe)
                lines += "$use  incompleteCode=$incompleteCode  multiResolve  " + reference.multiResolve(incompleteCode)
                    .joinToString(" | ") { "${location(it.element)}${if (it.isValidResult) "" else " (invalid)"}" }
                lines += "$use  incompleteCode=$incompleteCode  found  " +
                    Use.of(call)?.let { LegacyWalkSource.candidates(it, incompleteCode) }.orEmpty().joinToString(" | ") { found ->
                        val declaration = found.candidate.declaration
                        "${location(found.element)} ${declaration.name}/${declaration.arity} " +
                            "${found.candidate.reach} ${found.candidate.applicability} " +
                            "via=[${found.via.joinToString(",", transform = ::location)}]"
                    }
            }
        }

        guarded(lines, "$use  variants") {
            val visible = Variants.visible(call)
            val lookups = unqualifiedLookupElements(visible, appendParentheses = true)
            lines += "$use  variants  " + lookups.joinToString(" ") { "${it.lookupString}@${location(it.psiElement)}" }
            lines += "$use  visible  " + visible.joinToString(" ") { v ->
                "${v.lookupName}|${v.name}|${v.declaration?.let { "${it.name}/${it.arity}/${it.visibility}" }}@${location(v.element)}"
            }
        }

        return lines
    }

    /** A use whose resolve the platform's recursion guard refuses to cache is recorded, so the rest still dump. */
    private fun guarded(lines: MutableList<String>, use: String, block: () -> Unit) {
        try {
            block()
        } catch (exception: RuntimeException) {
            // `RecursionManager.CachingPreventedException` is internal API, so it is known by name.
            if (exception.javaClass.simpleName != "CachingPreventedException") throw exception
            lines += "$use  EXCEPTION ${exception.javaClass.simpleName}"
        }
    }

    private fun describe(result: VisitedElementSetResolveResult): String =
        "${location(result.element)}${if (result.isValidResult) "" else " (invalid)"} reach=${result.reach} " +
            "via=[${result.visitedElementSet.joinToString(",", transform = ::location)}]"

    fun location(element: PsiElement?): String {
        val file = element?.containingFile ?: return element.toString()
        val virtualFile = file.viewProvider.virtualFile
        val path = VfsUtilCore.getRelativePath(virtualFile, root) ?: virtualFile.name
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return "$path@${element.textOffset}"
        val offset = element.textRange.startOffset
        val line = document.getLineNumber(offset)

        return "$path:${line + 1}:${offset - document.getLineStartOffset(line) + 1}"
    }

    private companion object {
        var counter = 0
    }
}
