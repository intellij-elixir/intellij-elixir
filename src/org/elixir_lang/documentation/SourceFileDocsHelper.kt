package org.elixir_lang.documentation

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import org.elixir_lang.Module
import org.elixir_lang.NameArity
import org.elixir_lang.declaration.Declaration
import org.elixir_lang.declaration.Declared
import org.elixir_lang.declaration.Feature
import org.elixir_lang.declaration.ArityKnowledge
import org.elixir_lang.declaration.ClauseOrder
import org.elixir_lang.declaration.Found
import org.elixir_lang.declaration.Resolved
import org.elixir_lang.declaration.Use
import org.elixir_lang.declaration.clauseOrder
import org.elixir_lang.declaration.identity
import org.elixir_lang.psi.arityInterval
import org.elixir_lang.declaration.Form
import org.elixir_lang.declaration.Reach
import org.elixir_lang.declaration.sourceFor
import org.elixir_lang.lowering.AtomName
import org.elixir_lang.psi.ArityInterval
import org.elixir_lang.psi.scope.call_definition_clause.DeclaringForm
import org.elixir_lang.psi.scope.call_definition_clause.LegacyWalkSource
import org.elixir_lang.psi.scope.call_definition_clause.Declarations
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.CallDefinitionClause.enclosingModularMacroCall
import org.elixir_lang.psi.QuoteMacro
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.CanonicallyNamed
import org.elixir_lang.psi.impl.identifierName
import org.elixir_lang.psi.impl.siblingExpressions
import org.elixir_lang.psi.stub.type.call.Stub
import org.elixir_lang.code_insight.RecordedSignature
import org.elixir_lang.code_insight.definition
import org.elixir_lang.structure_view.element.CallDefinitionHead
import com.intellij.util.concurrency.annotations.RequiresReadLock

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
        // The form the use found the function declared with: a source that knows the macro's module gives it where
        // resolving the call can't, as for the SDK's `EEx` read from its `.beam`.
        else -> when (val form = (used?.candidate?.declaration?.declared as? Declared.Source)?.form ?: functionForm(call)) {
            Form.CLAUSE, Form.DELEGATION, Form.EXCEPTION -> declaredDocs(call, form, remote, used)
            Form.EEX_FUNCTION_FROM, Form.GENERATOR_EMBED -> templateDocs(call, form, remote, used)
            Form.CALLBACK, null -> null
        }
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
     * from every clause of it where the module defines it; for a decompiled module's, [index], one for each full arity.
     */
    @RequiresReadLock
    internal fun titles(index: String?, function: List<Written>): List<String> {
        val found = function.mapNotNull(Written::found)

        if (found.size == function.size) return listOfNotNull(function.first().identity?.let { RecordedSignature.of(found, it.arity) }?.text)

        // The bridge's decompiled clauses, in the order they are written.
        return function
            .groupBy { it.arities.definition() }
            .entries
            .sortedBy { it.key.first }
            .mapNotNull { (_, declarations) ->
                val first = declarations.first()
                val clauses = declarations.map { RecordedSignature.Clause(it.form, it.head) }

                RecordedSignature.of(index, first.declaration.name, first.arities, clauses)?.text
            }
    }

    /**
     * The docs of every function [declaring] shares a name with in its module that the use can reach, ascending by
     * arity: those [FetchedDocs.Arities.listed] lists, from outside the module where [remote]. The declarations that
     * can be one function, as a bodiless head's defaults and the clauses after it can, are one. Each is titled as
     * Elixir records it in the module that declares it, since a `use` injects into the module that calls `use`.
     */
    @RequiresReadLock
    private fun declaredDocs(declaring: Call, form: Form, remote: Boolean, used: Found?): FetchedDocs? {
        val index = enclosingModularMacroCall(declaring)?.let(::indexName)
        val module = index?.let(Module::inspect)
        val listing = listedFunctions(declaring, form, remote, used) ?: return null
        val functions = listing.functions

        val entries = functions.map { function ->
            val maxima = function.map { it.arities.maximum }
            val documentation = listing.documentation(function)
            // A form that writes no head, with no signature known, is named by its name and the arities it may have.
            val titles = titles(index, function).ifEmpty {
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
            kept[function.first().identity]?.documentation
                ?.let { FetchedDocs.FunctionOrMacroDocumentation(it.module, it.deprecated, it.doc, emptyList(), emptyList(), emptyList()) }

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
            val NONE = Kept(
                FetchedDocs.FunctionOrMacroDocumentation(null, null, null, emptyList(), emptyList(), emptyList()),
                hidden = false
            )
        }
    }

    /**
     * What Elixir keeps of the definitions that [functions], the functions of one name in one copy of a module,
     * replaced ([org.elixir_lang.declaration.CandidateSource.replaced]), by the function's identity, documented in
     * [module].
     */
    @RequiresReadLock
    private fun kept(functions: List<List<Written>>, module: String?): Map<NameArity?, Kept> {
        val first = functions.firstOrNull()?.firstOrNull() ?: return emptyMap()
        val modular = first.found?.let(Documented::copy) ?: return emptyMap()
        val name = first.declaration.name

        return sourceFor(Feature.DOCUMENTATION).replaced(modular, name)
            .mapNotNull { written(it, name, module) }
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
        if (listed.arity == ArityKnowledge.Unknown) return null
        val modular = enclosingModularMacroCall(declaring) ?: return null
        val module = moduleName(modular)
        // A compiled find is the `.beam`'s, not the source [declaring] is in.
        val sourceUsed = used?.takeIf { it.candidate.declaration.declared is Declared.Source }
        val defined = defined(sourceUsed?.let(Documented::copy) ?: modular, listed).orEmpty()
        val finds = finds(declaring, declaringForm, listed, sourceUsed, defined)
        val written = finds
            ?.let { (own, candidates) ->
                Documented.inCopyOf(own, candidates).mapNotNull { found ->
                    ProgressManager.checkCanceled()

                    written(found, listed.name, module)
                }
            }
            // A bridge until `located` gives a compiled find's clauses: a `.beam`'s finds are
            // one per export, not its decompiled clauses, so those are read from the decompiled module.
            ?: decompiledClauses(declaring, declaringForm, listed, modular, module)
                .takeIf { sourceUsed == null && (used != null || defined.any { it.candidate.declaration.declared is Declared.Compiled }) }
            ?: return null
        val usedIdentity = finds?.first?.identity() ?: listed.identity
        // One function per identity, as Elixir keys its definitions. A `def` replaces what `defexception` defines
        // rather than joining it, so that, like a declaration of no known identity, is one function alone. They are
        // ascending by the full arity that keys each, and each's clauses are in the order the module defines them.
        val functions = written.sortedWith(compareBy<Written> { it.arities.minimum }.thenBy(Written::order))
            .groupBy { one -> one.identity?.takeUnless { one.generated } ?: one }
            .values
            .sortedBy { function -> function.maxOf { it.arities.maximum ?: Int.MAX_VALUE } }
        val kept = kept(functions, module)

        return Listing(
            functions.filter { function ->
                FetchedDocs.Arities.listed(
                    inside = !remote,
                    exported = function.any { Reach.OWN.exports(it.declaration.capabilities) },
                    hidden = kept.of(function).hides(function),
                    // Where a `def` of its arity replaced what `defexception` defines, the use found the `def`.
                    used = function.any {
                        val identity = it.identity

                        if (it.generated || identity == null || usedIdentity == null) it.call == declaring else identity == usedIdentity
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

    /** The declarations of [listed]'s name [modular], a decompiled module, writes, as [listedFunctions] lists them. */
    @RequiresReadLock
    private fun decompiledClauses(declaring: Call, declaringForm: Form, listed: Declaration, modular: Call, module: String?): List<Written> {
        val state = ResolveState.initial()

        return CallDefinitionClause.modularChildCalls(modular)
            .flatMap { sibling ->
                ProgressManager.checkCanceled()

                val form = (if (sibling == declaring) declaringForm else DeclaringForm.shapedForm(sibling))
                    ?.takeUnless { it == Form.CALLBACK }
                    ?: return@flatMap emptyList()
                val named = Declarations.of(form, sibling, state)
                    .mapNotNull { it.declaration?.takeIf { declaration -> declaration.name == listed.name } }
                if (named.isEmpty() || (sibling != declaring && functionForm(sibling) != form)) return@flatMap emptyList()
                val hidden = hidesDoc(sibling)

                named.mapNotNull { declaration ->
                    val arities = declaration.arity.arityInterval() ?: return@mapNotNull null
                    val head = DeclaringForm.head(sibling, form, declaration.name, declaration.arity) ?: return@mapNotNull null

                    Written(null, sibling, form, head, declaration, arities, documentation(sibling, form, head, listed.name, module), hidden)
                }
            }
    }

    /** [declaring]'s find as [listed] among [candidates]. */
    private fun own(declaring: Call, listed: Declaration, candidates: List<Found>): Found? =
        candidates.firstOrNull { it.element == declaring && it.candidate.declaration == listed }
            ?: candidates.firstOrNull { it.element == declaring }

    /**
     * What the documentation source finds at [declaring]'s own head, the use it is of [listed]'s name. For a head a
     * `quote` returns, the walk's finds, as the expander reads no `quote`'s definitions.
     */
    @RequiresReadLock
    private fun atHead(declaring: Call, form: Form, listed: Declaration): List<Found>? {
        val head = DeclaringForm.head(declaring, form, listed.name, listed.arity)?.let(CallDefinitionHead::strip)
        val use = (head as? Call)?.let(Use::of) as? Use.Named ?: return null
        val source = if (enclosingModularMacroCall(declaring)?.let { QuoteMacro.`is`(it) } == true) LegacyWalkSource else sourceFor(Feature.DOCUMENTATION)

        return Resolved.candidates(use, incompleteCode = true, feature = Feature.DOCUMENTATION, source = source)
    }

    /**
     * What the documentation source finds [modular], a module, defines of [listed]'s name, at every arity, as a link
     * written in it would name them; `null` where [listed]'s arity is not known.
     */
    @RequiresReadLock
    private fun defined(modular: PsiElement, listed: Declaration): List<Found>? {
        val arity = listed.arity.arityInterval()?.minimum ?: return null

        // The source need walk no further than the first arity a use could call, unless the code is incomplete.
        return Resolved.candidates(Use.link(modular, modular, listed.name, arity), incompleteCode = true, feature = Feature.DOCUMENTATION)
    }

    /** [found] as one declaration [Written] of the function named [name], documented in [module]; `null` where it is none. */
    @RequiresReadLock
    private fun written(found: Found, name: String, module: String?): Written? {
        val call = found.element as? Call ?: return null
        val declaration = found.candidate.declaration.takeIf { it.name == name } ?: return null
        val form = (declaration.declared as? Declared.Source)?.form?.takeUnless { it == Form.CALLBACK } ?: return null
        val arities = declaration.arity.arityInterval() ?: return null
        val head = found.head.written ?: DeclaringForm.head(call, form, name, declaration.arity) ?: return null

        return Written(found, call, form, head, declaration, arities, documentation(call, form, head, name, module), hidesDoc(call))
    }

    /** The docs [call] writes for [head], one of its declarations of [name] with [form], documented in [module]. */
    @RequiresReadLock
    private fun documentation(call: Call, form: Form, head: PsiElement, name: String, module: String?): FetchedDocs.FunctionOrMacroDocumentation =
        if (form == Form.EXCEPTION) {
            FetchedDocs.FunctionOrMacroDocumentation(module, null, null, emptyList(), emptyList(), emptyList())
        } else {
            FetchedDocs.FunctionOrMacroDocumentation.fromCallDefinitionClauseCall(module, call, shownHead(call, form, head, name))
        }

    /** One `def`, `defmacro`, other clause, `defdelegate` or `defexception` function of a name, the [head] it writes, and the arities it covers. */
    internal class Written(
        val found: Found?,
        val call: Call,
        val form: Form,
        val head: PsiElement,
        val declaration: Declaration,
        val arities: ArityInterval,
        val documentation: FetchedDocs.FunctionOrMacroDocumentation,
        val hidden: Boolean
    ) {
        /** Whether `defexception` defines it, not a declaration written in the module. */
        val generated: Boolean get() = form == Form.EXCEPTION

        /** Where the module defines it. */
        @get:RequiresReadLock
        val order: ClauseOrder get() = found?.clauseOrder() ?: ClauseOrder.at(head)

        /** The function it is one definition of, as Elixir keys it. */
        val identity: NameArity? get() = found?.identity() ?: declaration.identity
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
        templateHead(call, form)?.let { head -> ownDocs(call, head)?.let { own -> declaredDocs(call, form, remote, used) ?: own } }

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

    /** The module [modular] is, as Elixir writes it, or `null` where it is none, as [indexName] says. */
    @RequiresReadLock
    internal fun moduleName(modular: PsiElement): String? = indexName(modular)?.let(Module::inspect)

    /** The index name of the module [modular] is, or `null` where it is none, as a `quote` is not: the module that calls `use` is not known here. */
    @RequiresReadLock
    internal fun indexName(modular: PsiElement): String? =
        (modular as? Call)
            ?.takeIf { Stub.isModular(it) }
            ?.let { (it as? CanonicallyNamed)?.canonicalName() }

    internal val HIDDEN_DOCUMENTATION = setOf("false", "nil")
}
