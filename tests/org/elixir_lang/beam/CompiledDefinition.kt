package org.elixir_lang.beam

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.ResolveState
import org.elixir_lang.NameArityInterval
import org.elixir_lang.beam.psi.BeamFileImpl
import org.elixir_lang.beam.psi.CallDefinition
import org.elixir_lang.expander.ExpanderSourceRows
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.arityKnowledge
import org.elixir_lang.psi.call.Call

/**
 * `<file> name/arity` for a compiled definition: a clause in a `.beam`'s decompiled source, named by that source's
 * file (`queue.beam.decompiled.ex`), or the `.beam`'s stub, named by the `.beam`; else `null`. Defaults widen the
 * arity to `minimum..maximum`.
 */
fun compiledDefinition(element: PsiElement?): String? =
    when (element) {
        is CallDefinition -> label(element.containingFile, element.nameArityInterval)
        is Call ->
            element
                .takeIf { it.containingFile.originalFile is BeamFileImpl && CallDefinitionClause.`is`(it) }
                ?.let { CallDefinitionClause.nameArityInterval(it, ResolveState.initial()) }
                ?.let { label(element.containingFile, it) }
        else -> null
    }

/** The reference's valid answers, each as [compiledDefinition] names it, else by its file, each once. */
fun PsiPolyVariantReference.validDefinitions(): List<String> =
    multiResolve(false).filter { it.isValidResult }.map { compiledDefinition(it.element) ?: it.element!!.containingFile.name }.distinct()

private fun label(file: PsiFile, nameArity: NameArityInterval): String =
    "${file.name} ${nameArity.name}/${ExpanderSourceRows.arity(nameArity.arityInterval.arityKnowledge())}"
