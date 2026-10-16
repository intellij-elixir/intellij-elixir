package org.elixir_lang.code_insight.completion.code_fragment

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionContributorEP
import com.intellij.codeInsight.completion.CompletionInitializationContext
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.openapi.Disposable
import com.intellij.openapi.extensions.DefaultPluginDescriptor
import com.intellij.openapi.extensions.LoadingOrder
import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.lowering.ElementLowering
import org.elixir_lang.lowering.ElixirAst
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

/**
 * Records what basic completion read: where its identifier ends, the dummy identifier it inserted at the caret, and
 * the file copy holding that dummy, lowered. Registered last, so it reads what the other contributors set.
 */
class CompletionRecorder : CompletionContributor() {
    override fun beforeCompletion(context: CompletionInitializationContext) {
        identifierEnd = context.identifierEndOffset
        dummyIdentifier = context.dummyIdentifier
    }

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        copy = ElementLowering.lower(parameters.position.containingFile)
    }

    /** What one completion recorded. */
    class Recorded(val identifierEnd: Int, val dummyIdentifier: String, val copy: ElixirAst)

    companion object {
        @Volatile
        private var identifierEnd: Int = -1

        @Volatile
        private var dummyIdentifier: String? = null

        @Volatile
        private var copy: ElixirAst? = null

        fun register(disposable: Disposable) {
            CompletionContributor.EP.point.registerExtension(
                CompletionContributorEP(
                    ElixirLanguage.id,
                    CompletionRecorder::class.java.name,
                    DefaultPluginDescriptor(CompletionRecorder::class.java.name),
                ),
                LoadingOrder.LAST,
                disposable,
            )
        }

        /** Runs [complete] and answers what it recorded, failing where completion ran no contributor. */
        fun record(description: String, complete: () -> Unit): Recorded {
            identifierEnd = -1
            dummyIdentifier = null
            copy = null

            complete()

            val identifierEnd = identifierEnd
            val dummyIdentifier = checkNotNull(dummyIdentifier) { "$description: no completion ran" }
            val copy = checkNotNull(copy) { "$description: completion filled no variants" }

            check(identifierEnd >= 0) { "$description: no completion ran" }

            return Recorded(identifierEnd, dummyIdentifier, copy)
        }
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
