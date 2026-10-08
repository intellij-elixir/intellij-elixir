package org.elixir_lang.model.psi.function

import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.call.Call
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition

/**
 * The definition clause [element] is: a source clause, or a `.beam` definition's clause in its decompiled mirror, which
 * builds that module's mirror once.
 */
@RequiresReadLock
fun definitionClause(element: PsiElement): Call? =
    when (element) {
        is BeamCallDefinition -> element.navigationElement as? Call
        is Call -> element
        else -> null
    }?.takeIf { CallDefinitionClause.`is`(it) }
