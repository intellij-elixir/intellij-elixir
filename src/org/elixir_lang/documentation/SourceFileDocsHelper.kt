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
import org.elixir_lang.structure_view.element.CallDefinitionHead
import org.elixir_lang.structure_view.element.Delegation
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
        else -> when (val form = functionForm(call)) {
            Form.CLAUSE, Form.DELEGATION -> declaredDocs(call, remote)
            Form.EEX_FUNCTION_FROM, Form.GENERATOR_EMBED -> templateDocs(call, form, remote)
            Form.CALLBACK, Form.EXCEPTION, null -> null
        }
    }

    /** The form [call] declares a function with: a `def*` clause, a `defdelegate`, an EEx function or an embed. */
    @RequiresReadLock
    private fun functionForm(call: Call): Form? =
        DeclaringForm.declaredBy(call)?.takeIf { form ->
            when (form) {
                Form.CLAUSE, Form.DELEGATION, Form.EEX_FUNCTION_FROM, Form.GENERATOR_EMBED -> true
                Form.CALLBACK, Form.EXCEPTION -> false
            }
        }

    /** What [declaring] declares with [form]: the one function its head names, when the name has an atom value. */
    @RequiresReadLock
    private fun declarationOf(declaring: Call, form: Form, state: ResolveState): Declaration? =
        Declarations.of(form, declaring, state).singleOrNull()?.declaration

    /** The head [declaring] shows its docs under, with [name] where it writes a head: `name/arity` for an EEx function or an embed. */
    @RequiresReadLock
    private fun headOf(declaring: Call, form: Form, name: String): String? =
        when (form) {
            Form.CLAUSE -> CallDefinitionClause.head(declaring)?.let { spelled(it, name) }
            Form.DELEGATION -> Delegation.head(declaring)?.let { spelled(it, name) }
            Form.EEX_FUNCTION_FROM, Form.GENERATOR_EMBED -> templateHead(declaring, form)
            Form.CALLBACK, Form.EXCEPTION -> null
        }

    /**
     * The docs of every function [declaring] shares a name with in its module that the use can reach, ascending by
     * arity: every one inside the module, and from outside ([remote]) not a private one, nor one marked `@doc false`
     * or `@doc nil`, unless it is the function [declaring] is. The declarations that can be one function, as a
     * bodiless head's defaults and the clauses after it can, are one.
     */
    @RequiresReadLock
    private fun declaredDocs(declaring: Call, remote: Boolean): FetchedDocs? {
        val module = enclosingModularMacroCall(declaring)?.let(::moduleName)
        val functions = listedFunctions(declaring, remote) ?: return null

        val entries = functions.map { function ->
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

    /** The `defdelegate`s among the functions [declaredDocs] lists for [declaring], which a delegation's block shows the targets of. */
    @RequiresReadLock
    internal fun listedDelegations(declaring: Call, remote: Boolean): List<Call> =
        listedFunctions(declaring, remote).orEmpty().flatten().filter { it.form == Form.DELEGATION }.map { it.call }

    /** The functions of [declaring]'s name that its use can reach, each the declarations that are one function, as [declaredDocs] describes. */
    @RequiresReadLock
    private fun listedFunctions(declaring: Call, remote: Boolean): List<List<Written>>? {
        val state = ResolveState.initial()
        val declaringForm = functionForm(declaring) ?: return null
        val used = declarationOf(declaring, declaringForm, state) ?: return null
        val usedArities = used.arity.arityInterval() ?: return null
        val modular = enclosingModularMacroCall(declaring) ?: return null
        val module = moduleName(modular)

        val written = CallDefinitionClause.modularChildCalls(modular)
            .mapNotNull { sibling ->
                ProgressManager.checkCanceled()

                val form = functionForm(sibling)
                val declaration = form?.let { declarationOf(sibling, it, state) }?.takeIf { it.name == used.name }
                val arities = declaration?.arity?.arityInterval()
                val head = form?.let { headOf(sibling, it, used.name) }

                if (form == null || declaration == null || arities == null || head == null) {
                    null
                } else {
                    Written(
                        sibling,
                        form,
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

        return functions.filter { function ->
            FetchedDocs.Arities.listed(
                inside = !remote,
                exported = function.any { Reach.OWN.exports(it.declaration.capabilities) },
                hidden = function.any { it.hidden },
                used = function.any { it.arities.overlaps(usedArities) }
            )
        }
    }

    /** One `def`, `defmacro`, other clause or `defdelegate` of a name, with the arities it covers. */
    private class Written(
        val call: Call,
        val form: Form,
        val declaration: Declaration,
        val arities: ArityInterval,
        val documentation: FetchedDocs.FunctionOrMacroDocumentation,
        val hidden: Boolean
    )

    /** The head an EEx function or an embed [call] declares with [form] shows its docs under, `name/arity`. */
    @RequiresReadLock
    private fun templateHead(call: Call, form: Form): String? =
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

    /**
     * The docs of an EEx function or an embed: those of every function of its name, once its arities are known, else its
     * own `@doc`. One with no `@doc` of its own has none.
     */
    @RequiresReadLock
    private fun templateDocs(call: Call, form: Form, remote: Boolean): FetchedDocs? =
        templateHead(call, form)?.let { head -> ownDocs(call, head)?.let { own -> declaredDocs(call, remote) ?: own } }

    /** The `@doc` written on an EEx function or an embed, shown with [head], or `null` when it has none. */
    @RequiresReadLock
    private fun ownDocs(call: Call, head: String): FetchedDocs? =
        enclosingModularMacroCall(call)?.let { modular ->
            FetchedDocs.FunctionOrMacroDocumentation
                .fromCallDefinitionClauseCall(moduleName(modular), call, head)
                .takeIf { it.doc != null }
        }

    /**
     * [head] written with [name], the name Elixir gives the function: a name written as a quoted atom, or with a
     * combining mark, reads as that atom. A head already written with it, or an operator's, is its own text.
     */
    @RequiresReadLock
    internal fun spelled(head: PsiElement, name: String): String {
        val stripped = CallDefinitionHead.strip(head) as? Call ?: return head.text
        val parameters = CallDefinitionHead.parameters(stripped)

        if (parameters == null || CallDefinitionHead.nameIdentifier(head)?.text == name) return head.text

        val guard = head.text.takeIf { it.startsWith(stripped.text) }?.removePrefix(stripped.text).orEmpty()

        return "$name(${parameters.joinToString(", ") { it.text }})$guard"
    }

    /** The module [modular] is, or `null` where it is none, as a `quote` is not: the module that calls `use` is not known here. */
    @RequiresReadLock
    internal fun moduleName(modular: PsiElement): String? =
        (modular as? Call)
            ?.takeIf { Stub.isModular(it) }
            ?.let { (it as? CanonicallyNamed)?.canonicalName() }
            ?.let { org.elixir_lang.Module.inspect(it) }

    internal val HIDDEN_DOCUMENTATION = setOf("false", "nil")
}
