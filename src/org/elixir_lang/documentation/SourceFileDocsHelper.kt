package org.elixir_lang.documentation

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.Module
import org.elixir_lang.NameArity
import org.elixir_lang.code_insight.RecordedSignature
import org.elixir_lang.declaration.ClauseOrder
import org.elixir_lang.declaration.Declaration
import org.elixir_lang.declaration.Declared
import org.elixir_lang.declaration.Feature
import org.elixir_lang.declaration.Form
import org.elixir_lang.declaration.Found
import org.elixir_lang.declaration.Reach
import org.elixir_lang.declaration.Resolved
import org.elixir_lang.declaration.Use
import org.elixir_lang.declaration.clauseOrder
import org.elixir_lang.declaration.declaringModuleName
import org.elixir_lang.declaration.definition
import org.elixir_lang.declaration.sourceFor
import org.elixir_lang.lowering.AtomName
import org.elixir_lang.model.psi.function.UseSymbols
import org.elixir_lang.psi.ArityInterval
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.CallDefinitionClause.enclosingModularMacroCall
import org.elixir_lang.psi.Unquote
import org.elixir_lang.psi.arityInterval
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.CanonicallyNamed
import org.elixir_lang.psi.impl.identifierName
import org.elixir_lang.psi.impl.siblingExpressions
import org.elixir_lang.psi.scope.call_definition_clause.Declarations
import org.elixir_lang.psi.scope.call_definition_clause.DeclaringForm
import org.elixir_lang.psi.scope.call_definition_clause.LegacyWalkSource
import org.elixir_lang.psi.stub.type.call.Stub
import org.elixir_lang.structure_view.element.CallDefinitionHead

object SourceFileDocsHelper {
    /**
     * The documentation of [element]; [remote] is whether the use is outside the module that defines it, as it is where
     * none is known, and [used] the find of [element] a use made, where one did, which picks the function where
     * [element] declares several and the module that declares it.
     */
    fun fetchDocs(element: PsiElement, remote: Boolean, used: Found? = null): FetchedDocs? =
        when (element) {
            is AtUnqualifiedNoParenthesesCall<*> -> fetchDocs(element)
            is Call -> fetchDocs(element, remote, used)
            else -> documentedCallDefinitionClause(element)?.let { fetchDocs(it, remote, used) }
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

    private fun fetchDocs(call: Call, remote: Boolean, used: Found?): FetchedDocs? = when {
        Stub.isModular(call) -> {
            // As in Elixir, the last `@moduledoc` replaces any before it, while a list is metadata that Elixir merges
            // into the doc. Any other value this cannot render, such as `File.read!(...)`, is passed over too, so the
            // doc before it is shown where Elixir would show the file.
            val moduleDoc = CallDefinitionClause.modularChildCalls(call)
                .asReversed()
                .asSequence()
                .filterIsInstance<AtUnqualifiedNoParenthesesCall<*>>()
                .filter { it.atIdentifier.identifierName() == "moduledoc" }
                .mapNotNull { it.moduleAttributeValue() }
                .firstOrNull { AtomName.of(it) in NO_MODULE_DOC || it.documentationMarkdownText() != null }
                ?.documentationMarkdownText()

            if (!moduleDoc.isNullOrEmpty()) {
                FetchedDocs.ModuleDocumentation(moduleName(call), moduleDoc)
            } else {
                null
            }
        }
        // The form the use found the function declared with: a source that knows the macro's module gives it where
        // resolving the call can't, as for the SDK's `EEx` read from its `.beam`.
        else -> {
            val form = (used?.candidate?.declaration?.declared as? Declared.Source)?.form ?: functionForm(call)
            val documented = used ?: form?.takeIf { declaredFunction(call, it) == null }?.let { placedFrom(call) }

            when (form) {
                Form.CLAUSE, Form.DELEGATION, Form.EXCEPTION -> declaredDocs(call, form, remote, documented)
                Form.EEX_FUNCTION_FROM, Form.GENERATOR_EMBED -> templateDocs(call, form, remote, documented)
                Form.CALLBACK, null -> null
            }
        }
    }

    /**
     * The head a macro's output places from [declaring], a call whose shape names a function that its form, read from
     * what it defines, does not: a user's macro shaped like `EEx.function_from_string` that quotes a `def`.
     */
    @RequiresReadLock
    private fun placedFrom(declaring: Call): Found? {
        val name = DeclaringForm.shapedForm(declaring)?.let { declaredFunction(declaring, it) }?.declaration?.name ?: return null
        val modular = enclosingModularMacroCall(declaring) ?: return null

        return defined(modular, name, 0).firstOrNull { it.head.placed && it.head.placedBy == declaring }
    }

    /** The form the documentation's source declares [call] with, where it declares a function: a `def*` clause, a `defdelegate`, a `defexception`, an EEx function or an embed. */
    @RequiresReadLock
    private fun functionForm(call: Call): Form? =
        sourceFor(Feature.DOCUMENTATION).declaredForm(call)
            ?.takeIf { form ->
                when (form) {
                    Form.CLAUSE, Form.DELEGATION, Form.EXCEPTION, Form.EEX_FUNCTION_FROM, Form.GENERATOR_EMBED -> true
                    Form.CALLBACK -> false
                }
            }

    /** What [declaring] declares with [form] as one function, spelled as its head names it: the one read of it. */
    @RequiresReadLock
    internal fun declaredFunction(declaring: Call, form: Form): Declarations.Spelled? =
        Declarations.of(form, declaring, ResolveState.initial()).singleOrNull()

    /**
     * [head], which [declaring] writes with [form], as its docs show it: after its keyword and with [name]. An EEx
     * function or an embed writes no head to show, so Elixir's signature heads its docs alone.
     */
    @RequiresReadLock
    private fun shownHead(declaring: Call, form: Form, head: PsiElement, name: String): String? =
        when (form) {
            Form.CLAUSE, Form.DELEGATION -> keyworded(declaring, spelled(head, name))
            Form.EEX_FUNCTION_FROM, Form.GENERATOR_EMBED, Form.CALLBACK, Form.EXCEPTION -> null
        }

    /** [written], the head [declaring] writes, as its docs show it: after [declaring]'s keyword. */
    @RequiresReadLock
    internal fun keyworded(declaring: Call, written: String): String? = declaring.functionName()?.let { "$it $written" }

    /**
     * The signature Elixir records for [function], the declarations of one function, in the module that declares it,
     * from every clause of it where the module defines it, those an override replaced among them.
     */
    @RequiresReadLock
    internal fun titles(function: List<Written>): List<String> =
        listOfNotNull(
            function.first().identity
                ?.let { RecordedSignature.ofOverridden(function.map(Written::found), it.arity, Feature.DOCUMENTATION) }
                ?.text
        )

    /**
     * The docs of every function [declaring] shares a name with in its module that the use can reach, ascending by
     * arity: those [FetchedDocs.Arities.listed] lists, from outside the module where [remote]. The declarations that
     * can be one function, as a bodiless head's defaults and the clauses after it can, are one. Each is titled as
     * Elixir records it in the module that declares it, since a `use` injects into the module that calls `use`.
     */
    @RequiresReadLock
    private fun declaredDocs(declaring: Call, form: Form, remote: Boolean, used: Found?): FetchedDocs? {
        val listing = listedFunctions(declaring, form, remote, used) ?: return null
        val functions = listing.functions
        // The module a find is defined in, as its title names it: a `use`'s `quote` defines into the module that calls `use`.
        val module = functions.asSequence().flatten().firstNotNullOfOrNull { it.documentation.module }
            ?: enclosingModularMacroCall(declaring)?.let(::moduleName)

        val entries = functions.map { function ->
            val maxima = function.map { it.arities.maximum }
            val documentation = listing.documentation(function)
            // A form that writes no head, with no signature known, is named by its name and the arities it may have.
            val titles = titles(function).ifEmpty {
                val first = function.first()

                if (documentation.heads.isEmpty()) listOf(templateHead(first.declaration.name, first.arities)) else emptyList()
            }

            FetchedDocs.Arities.Entry(
                ArityInterval(
                    function.minOf { it.arities.minimum },
                    if (null in maxima) null else maxima.filterNotNull().max()
                ),
                documentation.copy(titles = titles)
            )
        }

        return entries.takeIf(List<*>::isNotEmpty)?.let { FetchedDocs.Arities(module, it) }
    }

    /** The functions [declaredDocs] lists for [declaring], ascending by arity, each the declarations that are one function, which a delegation's block shows. */
    @RequiresReadLock
    internal fun listedGroups(declaring: Call, remote: Boolean): Listing =
        functionForm(declaring)?.let { listedFunctions(declaring, it, remote) } ?: Listing(emptyList(), emptyMap())

    /** The functions a listing shows, each the declarations that are one function. */
    internal class Listing(val functions: List<List<Written>>, private val kept: Map<NameArity?, Kept>) {
        /** What Elixir keeps of the docs written by the definitions [function] replaced: their doc and its metadata. */
        fun replacedDocs(function: List<Written>): FetchedDocs.FunctionOrMacroDocumentation? =
            kept[function.first().identity]?.documentation?.let { undocumented(it.module).replacing(it) }

        /** [function]'s docs as Elixir keeps them: those its [written] declarations write, over the definitions it replaced. */
        fun documentation(function: List<Written>, written: List<Written> = function): FetchedDocs.FunctionOrMacroDocumentation =
            kept.of(function).then(written, written.map(Written::documentation).reduce(FetchedDocs.FunctionOrMacroDocumentation::merge)).documentation
    }

    /**
     * What Elixir's docs row holds of the definitions a function replaced, taken in definition order: each one's doc,
     * `@doc false` included, where it writes one, else the earlier's, and its metadata over the earlier's
     * (`Module.compile_doc/6`).
     */
    internal class Kept(val documentation: FetchedDocs.FunctionOrMacroDocumentation, private val hidden: Boolean) {
        /** These once [function], whose declarations' docs merge to [documentation], is defined over them. */
        fun then(function: List<Written>, documentation: FetchedDocs.FunctionOrMacroDocumentation): Kept =
            Kept(
                documentation.replacing(this.documentation).let { if (function.any(Written::hidden)) it.copy(doc = documentation.doc) else it },
                hides(function)
            )

        /**
         * Whether [function], replacing these, is written `@doc false`, or writes no doc where these are `@doc false`:
         * only the function's own `@doc` replaces it (`Module.compile_doc/6`).
         */
        fun hides(function: List<Written>): Boolean =
            function.any { it.hidden } || (hidden && function.all { it.documentation.doc == null })

        companion object {
            /** What Elixir keeps where nothing was replaced. */
            val NONE = Kept(undocumented(null), hidden = false)
        }
    }

    /**
     * What Elixir keeps of the definitions of [name] replaced ([org.elixir_lang.declaration.CandidateSource.replaced])
     * in the copy of its module that [own] is in, by the identity of the function that replaced them.
     */
    @RequiresReadLock
    private fun kept(own: Found, name: String): Map<NameArity?, Kept> {
        val modular = Documented.copy(own) ?: return emptyMap()

        return sourceFor(Feature.DOCUMENTATION).replaced(modular, name)
            .mapNotNull { written(it, name) }
            .groupBy(Written::identity)
            .filterKeys { it != null }
            .mapValues { (_, written) -> written.fold(Kept.NONE) { kept, next -> kept.then(listOf(next), next.documentation) } }
    }

    /** What Elixir keeps of the definitions [function] replaced, by its identity. */
    private fun Map<NameArity?, Kept>.of(function: List<Written>): Kept = this[function.first().identity] ?: Kept.NONE

    /**
     * The functions of the name [used], else the one function [declaring] declares with [declaringForm], that its use
     * can reach, ascending by arity, each the declarations that are one function, as [declaredDocs] describes: those
     * the documentation source finds the module defines in the copy that declares it, so a clause a `use` injects is
     * among them and a `def` the module body never runs is not.
     */
    @RequiresReadLock
    private fun listedFunctions(declaring: Call, declaringForm: Form, remote: Boolean, used: Found? = null): Listing? {
        val listed = used?.candidate?.declaration ?: declaredFunction(declaring, declaringForm)?.declaration ?: return null
        val minimum = listed.arity.arityInterval()?.minimum ?: return null
        val modular = enclosingModularMacroCall(declaring) ?: return null
        // The source need walk no further than the first arity a use could call, unless the code is incomplete.
        val defined = defined(used?.let(Documented::copy) ?: modular, listed.name, minimum)
        val (own, candidates) = finds(declaring, declaringForm, listed, used, defined) ?: return null
        val written = Documented.inCopyOf(own, candidates).mapNotNull { found ->
            ProgressManager.checkCanceled()

            written(found, listed.name)
        }
        val usedDefinition = written.firstOrNull { it.found == own }?.definition ?: own.definition()
        // One function per definition, as Elixir keys them. A `def` replaces what `defexception` defines
        // rather than joining it, so that, like a declaration of no known identity, is one function alone. They are
        // ascending by the full arity that keys each, and each's clauses are in the order the module defines them.
        val functions = written.sortedWith(compareBy<Written> { it.arities.minimum }.thenBy(Written::order))
            .groupBy { one -> if (one.generated) one else one.definition }
            .values
            .sortedBy { function -> function.maxOf { it.arities.maximum ?: Int.MAX_VALUE } }
        val kept = kept(own, listed.name)

        return Listing(
            functions.filter { function ->
                FetchedDocs.Arities.listed(
                    inside = !remote,
                    exported = function.any { Reach.OWN.exports(it.declaration.capabilities) },
                    hidden = kept.of(function).hides(function),
                    // Where a `def` of its arity replaced what `defexception` defines, the use found the `def`.
                    used = function.any {
                        if (it.generated || it.identity == null || usedDefinition.identity == null) {
                            it.call == declaring
                        } else {
                            it.definition == usedDefinition
                        }
                    },
                    generated = function.any(Written::generated)
                )
            },
            kept
        )
    }

    /**
     * [declaring]'s find as [listed], [used] where a use made it, with the finds of every arity of its name in the module
     * that declares it among them: those [defined] holds, what the documentation source finds the module defines, else,
     * for a head in no module the source knows, as a `quote` is, what its own head names. `null` where neither finds
     * [declaring].
     */
    @RequiresReadLock
    private fun finds(declaring: Call, form: Form, listed: Declaration, used: Found?, defined: List<Found>): Pair<Found, List<Found>>? {
        // The use's find of a clause [defined] holds may differ from it in how it was reached, as an import's or one past
        // a stop does, and is that clause; past a stop the module may define nothing the source knows.
        if (used != null) return used to if (defined.any { it.element == used.element && it.head == used.head }) defined else defined + used

        return (own(declaring, listed, defined)?.let { it to defined })
            ?: atHead(declaring, form, listed)?.let { atHead -> own(declaring, listed, atHead)?.let { it to atHead } }
    }

    /** [declaring]'s find as [listed] among [candidates]. */
    private fun own(declaring: Call, listed: Declaration, candidates: List<Found>): Found? =
        candidates.firstOrNull { it.element == declaring && it.candidate.declaration == listed }
            ?: candidates.firstOrNull { it.element == declaring }

    /**
     * What the documentation source finds the module holds at [declaring]'s own head, the use it is of [listed]'s name:
     * past a stop the name index finds every module's definitions of that name there too. For a head a `quote` returns,
     * the walk's finds, as the expander reads no `quote`'s definitions.
     */
    @RequiresReadLock
    private fun atHead(declaring: Call, form: Form, listed: Declaration): List<Found>? {
        val (_, use) = UseSymbols.headUse(DeclaringForm.head(declaring, form, listed.name, listed.arity)?.let(CallDefinitionHead::strip))
            ?: return null
        val source = if (Unquote.quoting(declaring) != null) LegacyWalkSource else sourceFor(Feature.DOCUMENTATION)

        return Resolved.candidates(use, incompleteCode = true, feature = Feature.DOCUMENTATION, among = { it.candidate.reach.held }, source = source)
    }

    /**
     * What the documentation source finds [modular], a module, defines of [name], at every arity, as a link to it at
     * [arity] written in it would name them: what the module holds, or the name index finds, and not what it imports.
     */
    @RequiresReadLock
    private fun defined(modular: PsiElement, name: String, arity: Int): List<Found> =
        Resolved.candidates(Use.link(modular, modular, name, arity), incompleteCode = true, feature = Feature.DOCUMENTATION, among = ::inModule)

    /** Whether [found] is a definition the module holds, or one the name index finds, not what it imports. */
    private fun inModule(found: Found): Boolean = found.candidate.reach.held || found.candidate.reach == Reach.INDEXED

    /**
     * [found] as one declaration [Written] of the function named [name], documented in the module that declares it;
     * `null` where it is none.
     */
    @RequiresReadLock
    private fun written(found: Found, name: String): Written? {
        val call = found.element as? Call ?: return null
        val declaration = found.candidate.declaration.takeIf { it.name == name } ?: return null
        val form = (declaration.declared as? Declared.Source)?.form?.takeUnless { it == Form.CALLBACK } ?: return null
        val arities = declaration.arity.arityInterval() ?: return null
        val module = found.declaringModuleName()?.let(Module::inspect)
        // A placed head's call writes no head naming it, and may be the macro's clause or the call that injected it, whose docs are not its own.
        val head = found.head.written ?: return Written(found, form, arities, undocumented(module), hidden = false)

        val attributes = DeclarationAttributes.of(call)
        val documentation =
            if (form == Form.EXCEPTION) undocumented(module) else attributes.documentation(module, shownHead(call, form, head, name))

        return Written(found, form, arities, documentation, attributes.hidesDoc)
    }

    /** No docs, no head and no spec, in [module]. */
    private fun undocumented(module: String?): FetchedDocs.FunctionOrMacroDocumentation =
        FetchedDocs.FunctionOrMacroDocumentation(module, null, null, emptyList(), emptyList(), emptyList())

    /**
     * One `def`, `defmacro`, other clause, `defdelegate` or `defexception` function of a name, the [head] it writes, and
     * the arities it covers; a placed head writes none.
     */
    internal class Written(
        val found: Found,
        val form: Form,
        val arities: ArityInterval,
        val documentation: FetchedDocs.FunctionOrMacroDocumentation,
        val hidden: Boolean
    ) {
        val call: Call get() = found.element as Call

        val head: PsiElement? get() = found.head.written

        val declaration: Declaration get() = found.candidate.declaration

        /** Whether `defexception` defines it, not a declaration written in the module. */
        val generated: Boolean get() = form == Form.EXCEPTION

        /** Where the module defines it, else, where no call places it, its place in its file: read once, as a sort compares it. */
        @get:RequiresReadLock
        val order: ClauseOrder by lazy(LazyThreadSafetyMode.NONE) { found.clauseOrder() ?: ClauseOrder.at(head ?: found.element) }

        /** The definition it is one of, as Elixir keys it: read once, as it may read a `.beam`. */
        val definition: Found.Definition by lazy(LazyThreadSafetyMode.NONE) { found.definition() }

        /** The function it is one definition of, as Elixir keys it. */
        val identity: NameArity? get() = definition.identity
    }

    /** The head an EEx function or an embed [call] declares with [form] shows its docs under, `name/arity`. */
    @RequiresReadLock
    private fun templateHead(call: Call, form: Form): String? =
        declaredFunction(call, form)?.let { templateHead(it.text, it.declaration?.arity?.arityInterval()) }

    /** [name] with the [arities] it may have, as `name/arity`, or [name] alone where they are not known. */
    private fun templateHead(name: String, arities: ArityInterval?): String {
        val arity = when {
            arities == null -> ""
            arities.maximum == arities.minimum -> "/${arities.minimum}"
            arities.maximum == null -> "/${arities.minimum}.."
            else -> "/${arities.minimum}..${arities.maximum}"
        }

        return name + arity
    }

    /**
     * The docs of an EEx function or an embed: those of every function of its name, once its arities are known, else its
     * own `@doc`. One with no `@doc` of its own has none.
     */
    @RequiresReadLock
    private fun templateDocs(call: Call, form: Form, remote: Boolean, used: Found?): FetchedDocs? =
        // A name the call computes is not written, but the use found the function it names.
        (templateHead(call, form) ?: used?.candidate?.declaration?.let { templateHead(it.name, it.arity.arityInterval()) })
            ?.let { head -> ownDocs(call, head)?.let { own -> declaredDocs(call, form, remote, used) ?: own } }

    /** The `@doc` written on an EEx function or an embed, shown with [head], or `null` when it has none. */
    @RequiresReadLock
    private fun ownDocs(call: Call, head: String): FetchedDocs? =
        enclosingModularMacroCall(call)?.let { modular ->
            DeclarationAttributes.of(call).documentation(moduleName(modular), head).takeIf { it.doc != null }
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

    /** The module [modular] is, as Elixir writes it, or `null` where it is none, as [indexName] says. */
    @RequiresReadLock
    internal fun moduleName(modular: PsiElement): String? = indexName(modular)?.let(Module::inspect)

    /** The index name of the module [modular] is, or `null` where it is none, as a `quote` is not: the module that calls `use` is not known here. */
    @RequiresReadLock
    internal fun indexName(modular: PsiElement): String? =
        (modular as? Call)
            ?.takeIf { Stub.isModular(it) }
            ?.let { (it as? CanonicallyNamed)?.canonicalName() }

    private val NO_MODULE_DOC = setOf("false", "nil")
}
