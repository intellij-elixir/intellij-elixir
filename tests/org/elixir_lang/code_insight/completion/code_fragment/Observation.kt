package org.elixir_lang.code_insight.completion.code_fragment

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionInitializationContext
import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.DefinitionDeclaration
import org.elixir_lang.psi.QualifiableAlias
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.CanonicallyNamed
import org.elixir_lang.psi.call.Named
import org.elixir_lang.psi.stub.type.call.Stub
import org.elixir_lang.reference.Callable
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition
import org.elixir_lang.beam.psi.Module as BeamModule
import org.elixir_lang.psi.ModuleAttribute as PsiModuleAttribute

/** Records where completion's identifier ends. */
class IdentifierEndRecorder : CompletionContributor() {
    override fun beforeCompletion(context: CompletionInitializationContext) {
        identifierEnd = context.identifierEndOffset
    }

    companion object {
        @Volatile
        var identifierEnd: Int = -1
    }
}

/**
 * What an item's PSI [element] is, and for a call the module defining it, or for a module the module it names.
 * An item without an element is an attribute only where [cursor] names one being typed: inside a `defimpl` the
 * plugin offers `@for` and `@protocol` with none.
 */
@RequiresReadLock
fun kind(element: PsiElement?, cursor: CursorContext): Pair<Kind, String?> =
    when {
        element == null ->
            if (cursor is ModuleAttribute || cursor is Struct && cursor.inside is ModuleAttribute) Kind.ATTRIBUTE to null else Kind.OTHER to null
        element is Call && DefinitionDeclaration.`is`(element) ->
            Kind.CALL to (CallDefinitionClause.enclosingModularMacroCall(element) as? CanonicallyNamed)?.canonicalName()
        element is BeamCallDefinition -> Kind.CALL to element.parent.name
        element is Named && Stub.isModular(element) -> Kind.MODULE to (element as? CanonicallyNamed)?.canonicalName()
        element is QualifiableAlias -> Kind.MODULE to element.fullyQualifiedName()
        element is BeamModule -> Kind.MODULE to element.name
        Callable.isVariable(element) || Callable.isParameter(element) -> Kind.VARIABLE to null
        PsiModuleAttribute.isDeclaration(element) -> Kind.ATTRIBUTE to null
        else -> Kind.OTHER to null
    }
