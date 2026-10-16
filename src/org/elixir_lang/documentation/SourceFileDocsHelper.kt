package org.elixir_lang.documentation

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import org.elixir_lang.declaration.Declaration
import org.elixir_lang.declaration.SameFunction
import org.elixir_lang.psi.arityInterval
import org.elixir_lang.declaration.Form
import org.elixir_lang.declaration.Reach
import org.elixir_lang.lowering.AtomName
import org.elixir_lang.psi.ArityInterval
import org.elixir_lang.psi.scope.call_definition_clause.DeclaringForm
import org.elixir_lang.psi.scope.call_definition_clause.Declarations
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.CallDefinitionClause.enclosingModularMacroCall
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.CanonicallyNamed
import org.elixir_lang.psi.impl.identifierName
import org.elixir_lang.psi.impl.siblingExpressions
import org.elixir_lang.psi.stub.type.call.Stub
import org.elixir_lang.psi.impl.call.finalArguments
import com.intellij.util.concurrency.annotations.RequiresReadLock

object SourceFileDocsHelper {
    /** The documentation of [element]; [remote] is whether the use is outside the module that defines it, as it is where none is known. */
    fun fetchDocs(element: PsiElement, remote: Boolean): FetchedDocs? = when (element) {
        is AtUnqualifiedNoParenthesesCall<*> -> fetchDocs(element)
        is Call -> fetchDocs(element, remote)
        else -> documentedCallDefinitionClause(element)?.let { fetchDocs(it, remote) }
    }

    /**
     * The `def`/`defp` clause [element] names, when [element] is that clause's own name identifier.
     *
     * Resolution hands out the name identifier rather than the clause where a `PsiNamedElement` is
     * wanted - `HeexComponentResolver.declarationTarget` does, so Rename and Go To Declaration land
     * on the name - and the resulting element reaches Quick Docs unchanged.
     *
     * The nearest [Call] ancestor of a name identifier is the head's argument-list wrapper
     * (`button(assigns)`), so the walk continues to the first call definition clause. Requiring the
     * clause's name identifier to be [element] is what keeps the clause's `@doc` off everything else
     * inside it.
     */
    private fun documentedCallDefinitionClause(element: PsiElement): Call? =
        generateSequence(element.parent) { it.parent }
            .filterIsInstance<Call>()
            .firstOrNull { CallDefinitionClause.`is`(it) }
            ?.takeIf { CallDefinitionClause.nameIdentifier(it) == element }

    private fun fetchDocs(moduleAttribute: AtUnqualifiedNoParenthesesCall<*>): FetchedDocs? =
        when (moduleAttribute.atIdentifier.identifierName()) {
            "type", "typep", "opaque" -> fetchTypeDocs(moduleAttribute)
            "callback", "macrocallback" -> fetchCallbackDocs(moduleAttribute)
            else -> null
        }

    private fun fetchTypeDocs(moduleAttribute: AtUnqualifiedNoParenthesesCall<*>): FetchedDocs.TypeDocumentation? {
        val typeDoc = moduleAttribute
            .siblingExpressions(forward = false, withSelf = false)
            .filterIsInstance<AtUnqualifiedNoParenthesesCall<*>>()
            .firstOrNull { previousModuleAttribute ->
                previousModuleAttribute.atIdentifier.identifierName() == "typedoc"
            }
            ?.moduleAttributeValue()
            ?.documentationMarkdownText()

        return if (!typeDoc.isNullOrEmpty()) {
            enclosingModularMacroCall(moduleAttribute)?.let { modular ->
                val module = moduleName(modular)

                FetchedDocs.TypeDocumentation(module, moduleAttribute.text, typeDoc)
            }
        } else {
            null
        }
    }

    private fun fetchCallbackDocs(moduleAttribute: AtUnqualifiedNoParenthesesCall<*>): FetchedDocs.CallbackDocumentation? {
        val typeDoc = moduleAttribute
            .siblingExpressions(forward = false, withSelf = false)
            .filterIsInstance<AtUnqualifiedNoParenthesesCall<*>>()
            .firstOrNull { previousModuleAttribute ->
                previousModuleAttribute.atIdentifier.identifierName() == "doc"
            }
            ?.moduleAttributeValue()
            ?.documentationMarkdownText()

        return if (!typeDoc.isNullOrEmpty()) {
            enclosingModularMacroCall(moduleAttribute)?.let { modular ->
                val module = moduleName(modular)

                FetchedDocs.CallbackDocumentation(module, moduleAttribute.text, typeDoc)
            }
        } else {
            null
        }
    }

    private fun fetchDocs(call: Call, remote: Boolean): FetchedDocs? = when {
        Stub.isModular(call) -> {
            // As in Elixir, the last `@moduledoc` replaces any before it and `false` or `nil` hides it, while a list is
            // metadata that Elixir merges into the doc. Any other value this cannot render, such as
            // `File.read!(...)`, is passed over too, so the doc before it is shown where Elixir would show the file.
            val moduleDoc = CallDefinitionClause.modularChildCalls(call)
                .asReversed()
                .asSequence()
                .filterIsInstance<AtUnqualifiedNoParenthesesCall<*>>()
                .filter { it.atIdentifier.identifierName() == "moduledoc" }
                .mapNotNull { it.moduleAttributeValue() }
                .firstOrNull { AtomName.of(it) in HIDDEN_DOCUMENTATION || it.documentationMarkdownText() != null }
                ?.documentationMarkdownText()

            if (!moduleDoc.isNullOrEmpty()) {
                FetchedDocs.ModuleDocumentation(moduleName(call), moduleDoc)
            } else {
                null
            }
        }
        CallDefinitionClause.`is`(call) -> clauseDocs(call, remote)
        // An EEx function or an embed shows the functions beside it once its arities are known, else its own `@doc`.
        else -> declaredHead(call)?.let { head -> ownDocs(call, head)?.let { own -> clauseDocs(call, remote) ?: own } }
    }

    /** The form [call] declares a function with, beside the clauses of its name: a clause, an EEx function or an embed. */
    @RequiresReadLock
    private fun functionForm(call: Call): Form? =
        DeclaringForm.declaredBy(call)?.takeIf { form ->
            when (form) {
                Form.CLAUSE, Form.EEX_FUNCTION_FROM, Form.GENERATOR_EMBED -> true
                Form.DELEGATION, Form.CALLBACK, Form.EXCEPTION -> false
            }
        }

    /** What [declaring] declares with [form]: the one function its head names, when the name has an atom value. */
    @RequiresReadLock
    private fun declarationOf(declaring: Call, form: Form, state: ResolveState): Declaration? =
        Declarations.of(form, declaring, state).singleOrNull()?.declaration

    /** The head [declaring] shows its docs under: the one it writes, or `name/arity` for an EEx function or an embed. */
    @RequiresReadLock
    private fun headOf(declaring: Call, form: Form): String? =
        when (form) {
            Form.CLAUSE -> CallDefinitionClause.head(declaring)?.text
            Form.EEX_FUNCTION_FROM, Form.GENERATOR_EMBED -> declaredHead(declaring)
            Form.DELEGATION, Form.CALLBACK, Form.EXCEPTION -> null
        }

    /**
     * The docs of every function [clause] shares a name with in its module that the use can reach, ascending by
     * arity: every one inside the module, and from outside ([remote]) not a private one, nor one marked `@doc false`
     * or `@doc nil`, unless it is the function [clause] is. The declarations that can be one function, as a bodiless
     * head's defaults and the clauses after it can, are one.
     */
    @RequiresReadLock
    private fun clauseDocs(clause: Call, remote: Boolean): FetchedDocs? {
        val state = ResolveState.initial()
        val declaringForm = functionForm(clause) ?: return null
        val used = declarationOf(clause, declaringForm, state) ?: return null
        val usedArities = used.arity.arityInterval() ?: return null
        val modular = enclosingModularMacroCall(clause) ?: return null
        val module = moduleName(modular)

        val written = CallDefinitionClause.modularChildCalls(modular)
            .mapNotNull { sibling ->
                ProgressManager.checkCanceled()

                val form = functionForm(sibling)
                val declaration = form?.let { declarationOf(sibling, it, state) }?.takeIf { it.name == used.name }
                val arities = declaration?.arity?.arityInterval()
                val head = form?.let { headOf(sibling, it) }

                if (declaration == null || arities == null || head == null) {
                    null
                } else {
                    Written(
                        declaration,
                        arities,
                        FetchedDocs.FunctionOrMacroDocumentation.fromCallDefinitionClauseCall(module, sibling, head),
                        hidden = hidesDoc(sibling)
                    )
                }
            }
        val functions = mutableListOf<MutableList<Written>>()

        for (one in written.sortedBy { it.arities.minimum }) {
            ProgressManager.checkCanceled()

            val function = functions.firstOrNull { function ->
                function.any { it.declaration.sameFunction(one.declaration) == SameFunction.YES }
            }

            if (function == null) functions += mutableListOf(one) else function += one
        }

        val entries = functions
            .filter { function ->
                FetchedDocs.Arities.listed(
                    inside = !remote,
                    exported = function.any { Reach.OWN.exports(it.declaration.capabilities) },
                    hidden = function.any { it.hidden },
                    used = function.any { it.arities.overlaps(usedArities) }
                )
            }
            .map { function ->
                val maxima = function.map { it.arities.maximum }

                FetchedDocs.Arities.Entry(
                    ArityInterval(
                        function.minOf { it.arities.minimum },
                        if (null in maxima) null else maxima.filterNotNull().max()
                    ),
                    function.map { it.documentation }.reduce { acc, documentation -> acc.merge(documentation) }
                )
            }

        return entries.sortedBy { it.arities.minimum }.takeIf(List<*>::isNotEmpty)?.let { FetchedDocs.Arities(module, it) }
    }

    /** One `def`, `defmacro` or other clause of a name, with the arities it covers. */
    private class Written(
        val declaration: Declaration,
        val arities: ArityInterval,
        val documentation: FetchedDocs.FunctionOrMacroDocumentation,
        val hidden: Boolean
    )

    /** The head a delegation, an EEx function or an embed shows its docs under, or `null` when [call] is none of them. */
    @RequiresReadLock
    private fun declaredHead(call: Call): String? =
        when (val form = DeclaringForm.declaredBy(call)) {
            Form.DELEGATION -> call.finalArguments()?.takeIf { it.size == 2 }?.get(0)?.text
            Form.EEX_FUNCTION_FROM, Form.GENERATOR_EMBED ->
                Declarations.of(form, call, ResolveState.initial()).singleOrNull()?.let { named ->
                    val arities = named.declaration?.arity?.arityInterval()
                    val arity = when {
                        arities == null -> ""
                        arities.maximum == arities.minimum -> "/${arities.minimum}"
                        arities.maximum == null -> "/${arities.minimum}.."
                        else -> "/${arities.minimum}..${arities.maximum}"
                    }

                    named.text + arity
                }
            Form.CLAUSE, Form.CALLBACK, Form.EXCEPTION, null -> null
        }

    /**
     * The `@doc` written on a declaration that is not a clause, shown with [head], or `null` when it has none.
     *
     * A delegation's own `@doc` is the more specific answer, so it replaces the target's. Null rather
     * than an empty document is what lets the caller fall through to the target.
     */
    @RequiresReadLock
    private fun ownDocs(call: Call, head: String): FetchedDocs? =
        enclosingModularMacroCall(call)?.let { modular ->
            FetchedDocs.FunctionOrMacroDocumentation
                .fromCallDefinitionClauseCall(moduleName(modular), call, head)
                .takeIf { it.doc != null }
        }

    @RequiresReadLock
    private fun moduleName(modular: PsiElement): String =
        (modular as? CanonicallyNamed)?.canonicalName()?.let { org.elixir_lang.Module.inspect(it) }.orEmpty()

    internal val HIDDEN_DOCUMENTATION = setOf("false", "nil")
}
