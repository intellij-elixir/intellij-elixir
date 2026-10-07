package org.elixir_lang.goto_decompiled

import com.intellij.navigation.GotoRelatedItem
import com.intellij.navigation.GotoRelatedProvider
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.ResolveState
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.beam.psi.Module as BeamModule
import org.elixir_lang.declaration.Declaration
import org.elixir_lang.declaration.Form
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.Definition
import org.elixir_lang.psi.Modular
import org.elixir_lang.psi.NamedElement
import org.elixir_lang.psi.arityInterval
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.StubBased
import org.elixir_lang.psi.definition
import org.elixir_lang.psi.scope.call_definition_clause.DeclaringForm
import org.elixir_lang.psi.scope.call_definition_clause.Declarations
import org.elixir_lang.psi.stub.index.AllName
import org.elixir_lang.reference.resolver.narrowedScope

/**
 * Go To Related from source to the decompiled version of what a declaring call declares
 */
class Provider : GotoRelatedProvider() {
    override tailrec fun getItems(psiElement: PsiElement): List<GotoRelatedItem> {
        val items = (psiElement as? Call)?.let { items(it) }

        return if (items == null) {
            val parent = psiElement.parent

            if (parent != null && parent !is PsiFile) {
                getItems(parent)
            } else {
                emptyList()
            }
        } else {
            items
        }
    }

    /** `null` when [call] declares nothing, so the climb goes on. */
    @RequiresReadLock
    private fun items(call: Call): List<GotoRelatedItem>? {
        val form = DeclaringForm.shapedForm(call)

        val decompiledSet = when {
            form != null -> declaredDecompiledSet(form, call)
            definition(call)?.type == Definition.Type.MODULAR -> modularDefinerToDecompiledSet(call)
            else -> return null
        }

        return decompiledSet.map { Item(it) }
    }

    @RequiresReadLock
    private fun declaredDecompiledSet(form: Form, call: Call): Set<Call> =
        when (form) {
            Form.CLAUSE,
            Form.DELEGATION,
            Form.EXCEPTION,
            Form.EEX_FUNCTION_FROM,
            Form.GENERATOR_EMBED -> declaredFunctionsToDecompiledSet(form, call)
            Form.CALLBACK -> emptySet()
        }

    @RequiresReadLock
    private fun declaredFunctionsToDecompiledSet(form: Form, call: Call): Set<Call> {
        val modularDefiner = CallDefinitionClause.enclosingModularMacroCall(call)
            ?.takeIf { definition(it)?.type == Definition.Type.MODULAR }
            ?: return emptySet()
        val declared = declarations(form, call)

        return modularDefinerToDecompiledSet(modularDefiner)
            .flatMap { decompiledModularDefiner ->
                Modular
                    .callDefinitionClauseCallSequence(decompiledModularDefiner)
                    .filter { decompiledDefiner ->
                        declarations(Form.CLAUSE, decompiledDefiner).any { decompiled ->
                            val decompiledArity = decompiled.arity.arityInterval()

                            declared.any { declaration ->
                                val declaredArity = declaration.arity.arityInterval()

                                // An arity not yet knowable matches by name alone.
                                declaration.name == decompiled.name &&
                                    (declaredArity == null || decompiledArity == null || declaredArity.overlaps(decompiledArity))
                            }
                        }
                    }
                    .asIterable()
            }
            .toSet()
    }

    @RequiresReadLock
    private fun declarations(form: Form, call: Call): List<Declaration> =
        Declarations.of(form, call, ResolveState.initial()).mapNotNull { it.declaration }

    @RequiresReadLock
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
