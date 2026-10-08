package org.elixir_lang.code_insight.completion

import com.intellij.codeInsight.completion.CompletionUtil
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition
import org.elixir_lang.beam.psi.Module as BeamModule
import org.elixir_lang.code_insight.preferFunctionHeads
import org.elixir_lang.declaration.Declaration
import org.elixir_lang.declaration.Form
import org.elixir_lang.declaration.Visible
import org.elixir_lang.model.psi.ElixirUsageQueries
import org.elixir_lang.psi.call.Call
import org.elixir_lang.code_insight.lookup.element_renderer.CallDefinitionClause as CallDefinitionClauseRenderer
import com.intellij.psi.ResolveState
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.scope.call_definition_clause.Declarations
import org.elixir_lang.structure_view.element.CallDefinitionHead
import org.elixir_lang.structure_view.element.Delegation
import org.elixir_lang.psi.CallDefinitionClause as CallDefinitionClausePsi
import org.elixir_lang.code_insight.lookup.element_renderer.Delegation as DelegationRenderer
import org.elixir_lang.code_insight.completion.insert_handler.QualifiedName
import org.elixir_lang.Arity
import org.elixir_lang.NameArityInterval

/**
 * The function-name [LookupElement]s a modular ([scope]) offers when completing a **remote**
 * reference: one entry per public function name, preferring bare function heads. Only public
 * (exported) functions are offered because a remote / MFA dispatch (`Mod.fun(...)`,
 * `apply(Mod, :fun, args)`, `{Mod, :fun, arity}`) can never reach a private function - a private
 * function is callable only through a local unqualified call inside its own module. Handles both
 * source modules ([Call]) and BEAM-decompiled modules ([BeamModule]); any other element type yields
 * nothing.
 *
 * @param insertHandler how the name is written where it is completed.
 *
 * Shared by qualified `Mod.<caret>` completion
 * ([org.elixir_lang.code_insight.completion.provider.CallDefinitionClause]), capture completion
 * ([org.elixir_lang.reference.CaptureNameArity.getVariants]) and MFA atom completion
 * ([org.elixir_lang.model.psi.atom.AtomReference.getVariants]).
 */
fun callDefinitionClauseLookupElements(
    scope: PsiElement,
    insertHandler: QualifiedName = QualifiedName.CALL
): Iterable<LookupElement> = offers(scope).map { it.lookupElement(insertHandler) }

/**
 * The remote-completion [LookupElement]s offered by the set of [modulars] a modular name resolved to
 * (via [org.elixir_lang.psi.impl.maybeModularNameToModulars]).
 *
 * @param arity when not `null`, only names with a definition of that arity are offered, as a capture `&Mod.name/arity`
 *   needs.
 * @see callDefinitionClauseLookupElements for the per-modular contract and [insertHandler].
 */
fun callDefinitionClauseLookupElements(
    modulars: Collection<PsiElement>,
    insertHandler: QualifiedName = QualifiedName.CALL,
    arity: Arity? = null
): List<LookupElement> = offers(modulars, arity).map { it.lookupElement(insertHandler) }

/** What [callDefinitionClauseLookupElements] offers for [modulars], each entry with what it declares. */
@RequiresReadLock
fun callDefinitionClauseVisible(modulars: Collection<PsiElement>): List<Visible> {
    ThreadingAssertions.assertReadAccess()

    return offers(modulars).map(Offer::visible)
}

/** One function name a modular offers, from the [element] that declares it: [form] is `null` for a `.beam` export. */
private class Offer(val name: String, val element: PsiElement, val form: Form?) {
    fun lookupElement(insertHandler: QualifiedName): LookupElement =
        if (form == Form.DELEGATION) {
            LookupElementBuilder
                .createWithSmartPointer(name, element.inOriginalFile())
                .withLookupStrings(ElixirUsageQueries.lookupStrings(name, element.inOriginalFile()))
                .withRenderer(DelegationRenderer(name))
                .withInsertHandler(insertHandler)
        } else {
            lookupElement(name, element, insertHandler)
        }

    @RequiresReadLock
    fun visible(): Visible {
        ThreadingAssertions.assertReadAccess()

        val declaration: Declaration? = when (form) {
            null -> (element as BeamCallDefinition).declaration()
            else -> Declarations.of(form, element as Call, ResolveState.initial()).firstOrNull { it.text == name }?.declaration
        }

        return Visible(name, declaration?.name, declaration, element)
    }
}

private fun offers(modulars: Collection<PsiElement>, arity: Arity? = null): List<Offer> =
    modulars.flatMap { offers(it, arity) }

private fun offers(scope: PsiElement, arity: Arity? = null): List<Offer> = when (scope) {
    is Call -> offers(scope, arity)
    is BeamModule -> offers(scope, arity)
    else -> emptyList()
}

/** Whether [arity] is unconstrained or within [nameArityInterval]'s arities; an unknown interval is kept. */
private fun ofArity(nameArityInterval: NameArityInterval?, arity: Arity?): Boolean =
    arity == null || nameArityInterval == null || arity in nameArityInterval.arityInterval

private fun offers(scope: Call, arity: Arity?): List<Offer> {
    val childCalls = CallDefinitionClausePsi.modularChildCalls(scope)

    val publicClauses = childCalls
        .filter { CallDefinitionClausePsi.`is`(it) }
        .filter { CallDefinitionClausePsi.capabilities(it)?.public == true }
        .filter { ofArity(CallDefinitionClausePsi.nameArityInterval(it, ResolveState.initial()), arity) }

    val clauseOffers = preferFunctionHeads(publicClauses).map { (name, bestClause) -> Offer(name, bestClause, Form.CLAUSE) }
    val clauseNames = clauseOffers.map(Offer::name).toSet()

    return clauseOffers + delegationOffers(childCalls, clauseNames, arity)
}

/**
 * The functions this module declares only with `defdelegate`.
 *
 * `Delegation.is` and `CallDefinitionClause.is` are disjoint, so delegates need their own pass or they
 * are never offered. Names already in [clauseNames] are skipped so a `def` keeps its richer
 * presentation; visibility is not filtered because there is no `defdelegatep`.
 *
 * A delegate's insert handler appends parentheses like a `def`'s, so it inserts `Mod.values()` and opens
 * parameter info, and stays a bare name for a capture or an MFA atom, where a name is not a call.
 */
private fun delegationOffers(childCalls: List<Call>, clauseNames: Set<String>, arity: Arity?): List<Offer> =
    childCalls
        .filter { Delegation.`is`(it) }
        .mapNotNull { delegation ->
            delegation
                .finalArguments()
                ?.takeIf { it.size == 2 }
                ?.let { arguments -> CallDefinitionHead.nameArityInterval(arguments[0], ResolveState.initial()) }
                ?.takeIf { ofArity(it, arity) }
                ?.name
                ?.takeIf { it !in clauseNames }
                ?.let { name -> name to delegation }
        }
        .distinctBy { (name, _) -> name }
        .map { (name, delegation) -> Offer(name, delegation, Form.DELEGATION) }

private fun offers(moduleImpl: BeamModule, arity: Arity?): List<Offer> =
    moduleImpl.callDefinitions()
        .filter { it.isExported && ofArity(it.nameArityInterval, arity) }
        .mapNotNull { callDefinition ->
            // MaybeExported documents exportedName() as null only when isExported() is false.
            callDefinition.exportedName()?.let { Offer(it, callDefinition, null) }
        }

private fun lookupElement(name: String, element: PsiElement, insertHandler: QualifiedName): LookupElement =
    LookupElementBuilder
        .createWithSmartPointer(name, element.inOriginalFile())
        .withLookupStrings(ElixirUsageQueries.lookupStrings(name, element.inOriginalFile()))
        .withRenderer(CallDefinitionClauseRenderer(name))
        .withInsertHandler(insertHandler)

/**
 * [CompletionUtil.getOriginalOrSelf] hands back the copy rather than null when it cannot map, so a lookup
 * element built from its result silently pins the throwaway file. It fails two ways here: the declaration
 * enclosing the caret has a same-class node that runs past the translated end, and one the trailing dot
 * swallowed has no node of its own at all.
 */
private fun PsiElement.inOriginalFile(): PsiElement {
    val mapped = CompletionUtil.getOriginalOrSelf(this)
    val copyFile = mapped.containingFile ?: return mapped

    if (copyFile.isPhysical) return mapped

    val startOffset = mapped.textRange?.startOffset ?: return mapped
    val originalLeaf = copyFile.findElementAt(startOffset)
        ?.let(CompletionUtil::getOriginalOrSelf)
        ?.takeIf { it.containingFile?.isPhysical == true }
        ?: return mapped
    val originalOffset = originalLeaf.textRange.startOffset

    val sameClass = generateSequence(originalLeaf) { it.parent }
        .takeWhile { it !is PsiFile }
        .firstOrNull { mapped.javaClass.isInstance(it) && it.textRange?.startOffset == originalOffset }

    return sameClass ?: originalLeaf
}
