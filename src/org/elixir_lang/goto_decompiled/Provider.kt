package org.elixir_lang.goto_decompiled

import com.intellij.navigation.GotoRelatedItem
import com.intellij.navigation.GotoRelatedProvider
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.ResolveState
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import org.elixir_lang.beam.psi.Module as BeamModule
import org.elixir_lang.psi.CallableDeclaration
import org.elixir_lang.psi.Definition
import org.elixir_lang.psi.Modular
import org.elixir_lang.psi.NamedElement
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.StubBased
import org.elixir_lang.psi.definition
import org.elixir_lang.psi.stub.index.AllName
import org.elixir_lang.reference.resolver.narrowedScope

/**
 * Go To Related from source to decompiled version of the same function
 */
class Provider : GotoRelatedProvider() {
    override tailrec fun getItems(psiElement: PsiElement): List<GotoRelatedItem> {
        val definitionItems = if (psiElement is Call) {
            val declarations = CallableDeclaration.definitions(psiElement, ResolveState.initial())

            when {
                declarations.isNotEmpty() -> callableDefinerToDecompiledSet(psiElement, declarations).map { Item(it) }
                definition(psiElement)?.type == Definition.Type.MODULAR -> modularDefinerToDecompiledSet(psiElement).map { Item(it) }
                else -> null
            }
        } else {
            null
        }

        return if (definitionItems == null) {
            val parent = psiElement.parent

            if (parent != null && parent !is PsiFile) {
                getItems(parent)
            } else {
                emptyList()
            }
        } else {
            definitionItems
        }
    }

    /** The decompiled definitions of what [definer] declares, whichever form declares it. */
    private fun callableDefinerToDecompiledSet(definer: Call, declarations: List<CallableDeclaration.Declaration>): Set<Call> {
        val modularDefiner = callableDefinerToModularDefiner(definer) ?: return emptySet()
        val state = ResolveState.initial()

        return modularDefinerToDecompiledSet(modularDefiner)
            .flatMap { decompiledModularDefiner ->
                Modular
                    .callDefinitionClauseCallSequence(decompiledModularDefiner)
                    .filter { decompiledDefiner ->
                        CallableDeclaration.definitions(decompiledDefiner, state).any { decompiled ->
                            declarations.any { declaration ->
                                declaration.name == decompiled.name &&
                                    declaration.nameArityInterval().arityInterval.overlaps(decompiled.nameArityInterval().arityInterval)
                            }
                        }
                    }
                    .asIterable()
            }
            .toSet()
    }

    private tailrec fun callableDefinerToModularDefiner(ancestor: PsiElement): Call? {
        return if (ancestor is Call && definition(ancestor)?.type == Definition.Type.MODULAR) {
            ancestor
        } else if (ancestor is PsiFile) {
            null
        } else {
            callableDefinerToModularDefiner(ancestor.parent)
        }
    }

    /** The decompiled modules [modularDefiner] names, among what its module can see: its libraries and SDK included. */
    private fun modularDefinerToDecompiledSet(modularDefiner: Call): Set<Call> {
        val project = modularDefiner.project
        val scope = narrowedScope(modularDefiner, project)

        return if (modularDefiner is StubBased<*>) {
            modularDefiner
                    .canonicalNameSet()
                    .let { decompiledSet(project, scope, it) }
        } else {
            emptySet()
        }
    }

    private fun decompiledSet(
            project: Project,
            scope: GlobalSearchScope,
            canonicalNameIterable: Iterable<String>
    ): Set<Call> =
            canonicalNameIterable.flatMapTo(mutableSetOf()) { decompiledSet(project, scope, it) }

    private fun decompiledSet(project: Project, scope: GlobalSearchScope, canonicalName: String): Set<Call> =
        StubIndex.getElements(
                AllName.KEY,
                canonicalName,
                project,
                scope,
                NamedElement::class.java
        ).mapNotNull { namedElement ->
            (namedElement as? BeamModule)
                    ?.navigationElement
                    ?.let { it as Call }
        }.toSet()
}
