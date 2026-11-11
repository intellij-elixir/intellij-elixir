package org.elixir_lang.expander

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiCompiledFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.ResolveState
import com.intellij.psi.StubBasedPsiElement
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.beam.psi.CallDefinition
import org.elixir_lang.beam.psi.stubs.ModuleStub
import org.elixir_lang.call.Visibility
import org.elixir_lang.code_insight.eachNameOnce
import org.elixir_lang.declaration.Answer
import org.elixir_lang.declaration.Applicability
import org.elixir_lang.declaration.ArityKnowledge
import org.elixir_lang.declaration.Candidate
import org.elixir_lang.declaration.CandidateSource
import org.elixir_lang.declaration.CompiledOrigin
import org.elixir_lang.declaration.CompletionPosition
import org.elixir_lang.declaration.Declaration
import org.elixir_lang.declaration.Declared
import org.elixir_lang.declaration.Definer
import org.elixir_lang.declaration.Form
import org.elixir_lang.declaration.Found
import org.elixir_lang.declaration.Head
import org.elixir_lang.declaration.Reach
import org.elixir_lang.declaration.Uncertain
import org.elixir_lang.declaration.Use
import org.elixir_lang.declaration.Visible
import org.elixir_lang.declaration.importedArities
import org.elixir_lang.declaration.admits
import org.elixir_lang.Module
import org.elixir_lang.NameArity
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Lowering
import org.elixir_lang.psi.ArityInterval
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.Definition
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.NamedElement
import org.elixir_lang.psi.QualifiableAlias
import org.elixir_lang.psi.QuoteMacro
import org.elixir_lang.psi.arityInterval
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.SyntacticCall
import org.elixir_lang.psi.call.qualification.Qualified
import org.elixir_lang.psi.definition
import org.elixir_lang.psi.impl.indexName
import org.elixir_lang.psi.impl.stripAccessExpressionAndParentheses
import org.elixir_lang.psi.scope.call_definition_clause.DeclaringForm
import org.elixir_lang.psi.stub.index.AllName
import org.elixir_lang.psi.stub.type.Named
import org.elixir_lang.structure_view.element.CallDefinitionHead
import org.elixir_lang.structure_view.element.Delegation
import org.jetbrains.annotations.TestOnly
import java.util.EnumMap
import java.util.concurrent.ConcurrentHashMap
import org.elixir_lang.beam.psi.Module as BeamModule

/**
 * Candidates from the expansion: the env each use was expanded in, the definition table of the module it is in, and
 * each module's copy as Elixir loads it.
 */
internal object ExpanderSource : CandidateSource {
    private val indexedByName = Key.create<CachedValue<ConcurrentHashMap<String, List<Defined>>>>(
        "org.elixir_lang.expander.ExpanderSource.indexed",
    )

    @RequiresReadLock
    override fun candidates(use: Use, incompleteCode: Boolean): List<Found> = answer(use, incompleteCode).found

    /** The absence is the first gap the lookups met: the use's env mark, then each lookup's, in the order met. */
    @RequiresReadLock
    override fun answer(use: Use, incompleteCode: Boolean): Answer {
        ThreadingAssertions.assertReadAccess()

        return when (use) {
            is Use.OfCall ->
                inputs(use.entrance)?.let { answerWith(use, it) } ?: Answer(indexFound(use), Uncertain.NO_EXPANSION)
            is Use.Specified, is Use.Remote, is Use.Tag, is Use.Link -> Answer(emptyList(), Uncertain.NO_EXPANSION)
        }
    }

    /**
     * With no SDK inputs no env is reached, so [use]'s finds are what the name index defines its name as: after a
     * qualifier, in the module it names as written; else in any module, a private one only in the module the use is in.
     * None for a use with no name, after a qualifier that is neither an alias nor an atom, and in dumb mode.
     */
    private fun indexFound(use: Use.OfCall): List<Found> {
        val name = (use as? Use.Named)?.name ?: return emptyList()
        val call = use.entrance
        val file = call.containingFile as? ElixirFile ?: return emptyList()
        if (DumbService.isDumb(file.project)) return emptyList()
        val wanted: (String) -> Boolean =
            if (call is Qualified) writtenModule(call.qualifier()) ?: return emptyList() else { _ -> true }
        val own by lazy { ownModular(call)?.let(::indexNames).orEmpty() }
        val indexed = indexed(file, name, use.arity, { it in own }, wanted)

        return beside(emptyList(), indexed, name, use.arity, remote = call is Qualified).mapNotNull { found(it, use, null) }
    }

    /** [use]'s candidates, its file expanded with [inputs] in place of its SDK's. */
    @TestOnly
    @RequiresReadLock
    internal fun candidates(use: Use.OfCall, inputs: ProjectModules.Inputs): List<Found> = answer(use, inputs).found

    /** [use]'s answer, its file expanded with [inputs] in place of its SDK's. */
    @TestOnly
    @RequiresReadLock
    internal fun answer(use: Use.OfCall, inputs: ProjectModules.Inputs): Answer {
        ThreadingAssertions.assertReadAccess()

        return answerWith(use, inputs)
    }

    private fun answerWith(use: Use.OfCall, inputs: ProjectModules.Inputs): Answer {
        val at = at(use.entrance, inputs) ?: return Answer(emptyList(), Uncertain.NO_EXPANSION)
        val found = answer(at, use)

        return Answer(found, at.gaps.firstOrNull())
    }

    private fun answer(at: At, use: Use.OfCall): List<Found> {
        val call = use.entrance
        val name = (use as? Use.Named)?.name
        val reached = if (call is Qualified) {
            val module = receiver(at, call.qualifier()) ?: return at.gap(Uncertain.MODULE)
            qualified(at, call.qualifier(), module, name, use.arity)
        } else {
            buildList {
                val defined = ownDefined(at, name)
                // This lookup is the use's first, so a MODULE gap is one it met: what its module defines isn't known.
                val ownUnknown = Uncertain.MODULE in at.gaps
                addAll(defined.flatMap { held(at, it, name, use.arity, remote = false, emptySet()) })
                addAll(imported(at, at.file, at.env, name, use.arity, emptySet()))
                addAll(specialForms(at, name, use.arity))
                // Past where the env is known, what the code after it brings in may be any definition of the name; on
                // reached code in a module not known to its end, any the module defines.
                val isOwn = isOwn(at)
                val wanted: ((String) -> Boolean)? = when {
                    at.uncertain != null -> { _ -> true }
                    ownUnknown -> isOwn
                    else -> null
                }
                if (name != null && wanted != null) {
                    addAll(beside(this, indexed(at.file, name, use.arity, isOwn, wanted), name, use.arity, remote = false))
                }
            }
        }

        return reached.mapNotNull { found(it, use, at.uncertain) }
    }

    /**
     * [module], which [qualifier] names, and its definitions of [name], as a remote call reaches them. Where none of
     * them takes [arity] arguments, also the index's: in [module] when what it defines isn't known to its end, or past
     * where the env is known when no module is [module]; and past where the env is known, in each module [qualifier]
     * may name as written ([aliasNames]), as an `alias` written after that point is missing from the env.
     */
    private fun qualified(at: At, qualifier: PsiElement, module: String, name: String?, arity: Int): List<Reached> {
        val found = inModule(at, module, name, arity, imports = false, emptySet())
        if (name == null || found.any { it.defined.declaration.name == name && it.defined.admits(arity) }) return found
        // This lookup is the use's first, so a MODULE gap is one it met.
        val unknown = Uncertain.MODULE in at.gaps ||
            (at.uncertain != null && at.holder.locate(at.file, module, at.inputs) == ProjectModules.Located.None)
        val key = Module.indexName(module).takeIf { unknown }
        val aliased = if (at.uncertain == null) null else aliasNames(qualifier)
        if (key == null && aliased == null) return found

        val indexed = indexed(at.file, name, arity, isOwn(at)) { it == key || aliased?.invoke(it) == true }

        return found + beside(found, indexed, name, arity, remote = true)
    }

    /**
     * Whether a module is the one [qualifier] names as written: an atom's, or an alias's ([aliasNames]); `null` for any
     * other qualifier.
     */
    private fun writtenModule(qualifier: PsiElement): ((String) -> Boolean)? =
        when (val written = qualifier.stripAccessExpressionAndParentheses()) {
            is ElixirAtom -> written.indexName()?.let { atom -> { module -> module == atom } }
            else -> aliasNames(qualifier)
        }

    /**
     * Whether a module may be the one the alias [qualifier] names, as an `alias` may name it: the alias itself, or
     * one whose name ends in `.` and the alias; only the module it spells after `Elixir.`, which no `alias` expands;
     * `null` where [qualifier] isn't an alias.
     */
    private fun aliasNames(qualifier: PsiElement): ((String) -> Boolean)? =
        (qualifier.stripAccessExpressionAndParentheses() as? QualifiableAlias)?.fullyQualifiedName()?.let { alias ->
            val rooted = Module.inspectedAlias(alias)
            val suffix = ".$alias"
            if (rooted != null) { module -> module == rooted } else { module -> module == alias || module.endsWith(suffix) }
        }

    /** Whether a module is the one [at] is in, by its index name. */
    private fun isOwn(at: At): (String) -> Boolean {
        val own = at.env.module?.let(Module::indexName)

        return { it == own }
    }

    /**
     * At an unqualified call, the definitions of the module it is in, what its env imports and `Kernel.SpecialForms`;
     * after a qualifier, the public definitions of the module it names, as completion offers them ([eachNameOnce]), with
     * a clause that takes a capture's arity for a capture.
     */
    @RequiresReadLock
    override fun visible(position: CompletionPosition): List<Visible> {
        ThreadingAssertions.assertReadAccess()

        return inputs(position.element)?.let { visibleWith(position, it) }.orEmpty()
    }

    /** What is visible at the call [position], its file expanded with [inputs] in place of its SDK's. */
    @TestOnly
    @RequiresReadLock
    internal fun visible(position: Call, inputs: ProjectModules.Inputs): List<Visible> {
        ThreadingAssertions.assertReadAccess()

        return visibleWith(CompletionPosition.OfCall(position), inputs)
    }

    private fun visibleWith(position: CompletionPosition, inputs: ProjectModules.Inputs): List<Visible> =
        when (position) {
            is CompletionPosition.OfCall ->
                if (position.call is Qualified) visibleAfterQualifier(position, inputs, null) else visibleAt(position.call, inputs)
            is CompletionPosition.AfterDot, is CompletionPosition.Mfa -> visibleAfterQualifier(position, inputs, null)
            is CompletionPosition.Capture -> visibleAfterQualifier(position, inputs, position.arity)
        }

    /**
     * The public definitions of the module [position] names, with a clause that takes [arity] arguments where it is not
     * `null`, as a capture of that arity needs.
     */
    private fun visibleAfterQualifier(position: CompletionPosition, inputs: ProjectModules.Inputs, arity: Int?): List<Visible> {
        val at = at(position.element, inputs) ?: return emptyList()
        val module = position.module?.let { receiver(at, it) } ?: return emptyList()

        return eachNameOnce(
            ofModule(at, module, null).filter { it.declaration.visibility != Visibility.PRIVATE && it.admits(arity) },
        ).mapNotNull { visible(it, null) }
    }

    private fun visibleAt(position: Call, inputs: ProjectModules.Inputs): List<Visible> {
        val at = at(position, inputs) ?: return emptyList()

        return buildList {
            ownDefined(at, null).mapNotNullTo(this) { visible(it, null) }
            importedDefined(at, at.file, at.env, null).mapNotNullTo(this) { (defined, _, _, arities) ->
                visible(defined, importedArities(defined.declaration.arity) { it in arities })
            }
            specialFormsDefined(at, null).mapNotNullTo(this) { visible(it, null) }
        }
    }

    /** `null` for a definition that may not be Elixir's, which completion doesn't offer. */
    private fun visible(defined: Defined, importedArities: Set<Int>?): Visible? =
        if (defined.uncertain == null) {
            Visible(defined.declaration.name, defined.declaration.name, defined.declaration, defined.element, importedArities)
        } else {
            null
        }

    /**
     * [defined] as completion offers a module's functions: of a source module's, the preferred head of each name its
     * own clauses define, then one `defdelegate` or head placed at its macro's clause for each other name; each of a
     * `.beam`'s.
     */
    private fun eachNameOnce(defined: List<Defined>): List<Defined> {
        val (source, compiled) = defined.partition { it.declaration.declared is Declared.Source }
        val (delegations, clauses) = source.partition { (it.declaration.declared as Declared.Source).form == Form.DELEGATION }
        val (own, placed) = clauses.partition { declares(it.element, it.declaration.name) }
        val byElement = own.associateBy { it.element }
        val calls = own.map { it.element as Call }

        return eachNameOnce(calls, (delegations + placed).map { it.declaration.name to it }) { _, clause ->
            byElement.getValue(clause)
        } + compiled
    }

    /**
     * Where a use is, as its file's expansion saw it, what each receiver it reached expanded to, the [lowering] it
     * read the file through, and why [env] may not be the env Elixir expands the use in, [uncertain], `null` where it is.
     *
     * @property gaps why a lookup from here could not say a name is missing, [uncertain] first, then in the order met
     */
    private class At(
        val file: ElixirFile,
        val range: TextRange,
        val inputs: ProjectModules.Inputs,
        val env: Env,
        val holder: ProjectModules,
        val receivers: ReceiverIndex,
        val lowering: Lowering,
        val uncertain: Uncertain?,
    ) {
        val gaps: MutableSet<Uncertain> = LinkedHashSet<Uncertain>().apply { uncertain?.let(::add) }

        /** Records [uncertain] as a gap, and finds nothing. */
        fun gap(uncertain: Uncertain): List<Nothing> {
            gaps.add(uncertain)

            return emptyList()
        }
    }

    /** `null` where the file's SDK gives no inputs. */
    private fun inputs(element: PsiElement): ProjectModules.Inputs? {
        val file = element.containingFile as? ElixirFile ?: return null

        return (SdkInputs.getInstance(file.project).inputsFor(file) as? SdkInputs.Answer.Ready)?.inputs
    }

    /** `null` in dumb mode. */
    private fun at(element: PsiElement, inputs: ProjectModules.Inputs): At? {
        val file = element.containingFile as? ElixirFile ?: return null
        if (DumbService.isDumb(file.project)) return null
        val holder = ProjectModules.getInstance(file.project)

        val indexed = holder.indexed(file, inputs)

        val placed = indexed.envs.placed(element.textRange)

        return At(file, element.textRange, inputs, placed.env, holder, indexed.receivers, indexed.lowering, placed.uncertain)
    }

    /**
     * A declaration, the PSI that declares it, the module whose definitions it is, and the delegation its head makes.
     *
     * @property written the head written for it, as [Head.written] is, `null` where nothing written declares it
     * @property module the module's index name (`Module.indexName`), as [Head.module] has it
     * @property injectedBy the call whose evaluated output injected it into [module], as written in [module]'s file
     * @property uncertain why it may not be a definition Elixir has, `null` where it is
     */
    private class Defined(
        val declaration: Declaration,
        val element: PsiElement,
        val written: PsiElement?,
        val module: String,
        val injectedBy: Call?,
        val delegation: DefinitionTable.Delegation? = null,
        val uncertain: Uncertain? = null,
    ) {
        /** Its head, which tells apart the heads of a list of `defdelegate` heads, and the modules one macro's output declares it in. */
        val head: Head get() = Head(written, module, declaration.name, declaration.arity)

        fun marked(uncertain: Uncertain): Defined =
            Defined(declaration, element, written, module, injectedBy, delegation, this.uncertain ?: uncertain)

        /** Whether [declaration] takes [arity] arguments, which is not known past a head with no largest arity. */
        fun admits(arity: Int?): Boolean = arity == null || declaration.arity.admits(arity)

        /** Its module and name, which are one Elixir function among the definitions admitting one arity. */
        val key: Pair<String, String> get() = module to declaration.name

        /** Its declaration and the PSI declaring it, which the exports or copies answered by one written head share. */
        val declaredAt: Pair<PsiElement, Declaration> get() = element to declaration
    }

    /**
     * A declaration reached for a use.
     *
     * @property paths the `import` calls crossed, outermost first
     * @property delegations the `defdelegate` calls crossed, from the use outward
     * @property heads the arity each of [delegations] declares in its head
     * @property through the head of the first of [delegations], the one the use reached itself
     * @property searched the name looked up where it was found
     * @property named whether everything but the arity fits through every head crossed: each name, and [imported]
     * @property imported whether each `import` crossed that leaves out some of a definition's arities brings it in at
     *   the arity reaching it, which a use with no name needs as well
     * @property remote whether a remote call reaches it, which a private definition refuses
     */
    private class Reached(
        val defined: Defined,
        val reach: Reach,
        val paths: List<Call>,
        val delegations: List<Call>,
        val heads: List<ArityKnowledge>,
        val through: Head?,
        val searched: String?,
        val named: Boolean,
        val imported: Boolean,
        val remote: Boolean,
    )

    /**
     * [defined], which its module holds, looked up there as [name] at [arity]: through the call whose output injected
     * it, as [Reach.USE], or else as [Reach.OWN].
     */
    private fun held(at: At, defined: Defined, name: String?, arity: Int?, remote: Boolean, crossed: Set<Head>): List<Reached> {
        val reach = if (defined.injectedBy == null) Reach.OWN else Reach.USE

        return reachedFrom(at, defined, reach, listOfNotNull(defined.injectedBy), name, arity, remote, crossed)
    }

    /** The definitions of [name], or of every name when it is `null`, in the module body [at] is in. */
    private fun ownDefined(at: At, name: String?): List<Defined> {
        val module = at.env.module ?: return emptyList()
        val result = ProjectModules.results(at.holder.expansion(at.file, at.inputs)).firstOrNull { result ->
            result.module == module &&
                result.units.any { it.owner == ExpansionResult.Owner.ModuleBody && it.node.meta.origin.contains(at.range) }
        } ?: return at.gap(Uncertain.MODULE)
        if (!result.ownDefinitionsKnown) at.gap(Uncertain.MODULE)

        return defined(at.file, result, name)
    }

    /** What [env]'s imports of [name] reach, each through its `import` call in [file]. */
    private fun imported(
        at: At,
        file: ElixirFile,
        env: Env,
        name: String?,
        arity: Int?,
        crossed: Set<Head>,
    ): List<Reached> =
        importedDefined(at, file, env, name).flatMap { (defined, reach, call, arities) ->
            val reached = reached(defined, reach, listOfNotNull(call), name, arity, remote = false, arities)
            val admits = defined.admits(arity) && (arity == null || arity in arities)

            listOf(reached) + throughDelegation(at, reached, reached.named, admits, crossed)
        }

    /**
     * A definition an import brings in, how, the `import` call that brings it in, if any, and the [arities] of it that
     * the import brings in.
     */
    private data class Imported(val defined: Defined, val reach: Reach, val call: Call?, val arities: Set<Int>)

    /** The definitions [env]'s imports bring in of [name], or of every name when it is `null`. */
    private fun importedDefined(at: At, file: ElixirFile, env: Env, name: String?): List<Imported> =
        (env.functions + env.macros).flatMap { imports ->
            val aritiesByName = NameIndex.of(imports).named(name).groupBy({ it.name }, { it.arity })
            if (aritiesByName.isEmpty()) return@flatMap emptyList()
            // An `import` a macro's output added is reached through the call that injected it.
            val call = imports.injectedBy?.let { injectingCall(file, it) } ?: imports.origin?.let { importCall(file, it) }
            // The `Kernel` import a `defprotocol` builds has the `defprotocol`'s origin, and no `import` call names it.
            val reach = if (call == null && imports.module == KERNEL) Reach.IMPLICIT_IMPORT else Reach.IMPORT

            aritiesByName.flatMap { (imported, arities) ->
                ofModule(at, imports.module, imported)
                    .filter { bringsIn(it, arities) }
                    .map { Imported(it, reach, call, arities.toSet()) }
            }
        }

    /** `Kernel.SpecialForms`, which no env lists, as Elixir imports it everywhere. */
    private fun specialForms(at: At, name: String?, arity: Int): List<Reached> =
        specialFormsDefined(at, name).flatMap {
            reachedFrom(at, it, Reach.IMPLICIT_IMPORT, emptyList(), name, arity, remote = false, emptySet())
        }

    /**
     * `Kernel.SpecialForms`' declarations of [name], or of every name when it is `null`, at the arities Elixir takes
     * each at, which its declared arities leave out.
     */
    private fun specialFormsDefined(at: At, name: String?): List<Defined> =
        ofModule(at, SPECIAL_FORMS, name).map { defined ->
            val arity = specialFormArity(defined.declaration.name, at.inputs.level) ?: return@map defined

            Defined(
                defined.declaration.copy(arity = arity),
                defined.element,
                defined.written,
                defined.module,
                defined.injectedBy,
                defined.delegation,
                defined.uncertain,
            )
        }

    /**
     * [module]'s definitions of [name], as a remote call reaches them, and with [imports] what the env at the end of
     * its body imports, which a delegation's target module also looks up.
     */
    private fun inModule(
        at: At,
        module: String,
        name: String?,
        arity: Int?,
        imports: Boolean,
        crossed: Set<Head>,
    ): List<Reached> {
        ProgressManager.checkCanceled()

        val located = at.holder.locate(at.file, module, at.inputs)
        val own = definedIn(at, located, module, name).flatMap { held(at, it, name, arity, remote = true, crossed) }
        val source = located as? ProjectModules.Located.Source ?: return own
        val result = source.result?.takeIf { imports } ?: return own
        val body = bodyEnv(result) ?: return own.also { at.gap(Uncertain.MODULE) }

        return own + imported(at, source.file, body, name, arity, crossed)
    }

    /** [defined], looked up as [name] at [arity], and what it reaches when it delegates. */
    private fun reachedFrom(
        at: At,
        defined: Defined,
        reach: Reach,
        paths: List<Call>,
        name: String?,
        arity: Int?,
        remote: Boolean,
        crossed: Set<Head>,
    ): List<Reached> {
        ProgressManager.checkCanceled()

        val reached = reached(defined, reach, paths, name, arity, remote)

        return listOf(reached) + throughDelegation(at, reached, reached.named, defined.admits(arity), crossed)
    }

    /**
     * [defined], looked up as [name] at [arity], which is `null` past a head with no largest arity. An `import` that
     * brings in only the arities [imported] of it names it at no other, as the walk's `importAdmits` decides.
     */
    private fun reached(
        defined: Defined,
        reach: Reach,
        paths: List<Call>,
        name: String?,
        arity: Int?,
        remote: Boolean,
        imported: Set<Int>? = null,
    ): Reached {
        val admitted = arity == null || imported == null || arity in imported
        val named = name != null && defined.declaration.name == name && admitted

        return Reached(defined, reach, paths, emptyList(), emptyList(), null, name, named, admitted, remote)
    }

    /**
     * What [delegation] reaches: its target module's lookup of the function it calls, at its head's largest arity,
     * named only where [named] is. A `defdelegate` head already crossed is not crossed again. A head the use reaches
     * itself, with nothing [crossed], is followed whatever its validity; one reached through a delegation only where
     * it [admits] the arity that delegation calls it at, as no other head runs. A target keeps the mark of a head
     * that may not be the one Elixir loads.
     */
    private fun throughDelegation(
        at: At,
        delegation: Reached,
        named: Boolean,
        admits: Boolean,
        crossed: Set<Head>,
    ): List<Reached> {
        val target = delegation.defined.delegation ?: return emptyList()
        if (crossed.isNotEmpty() && !admits) return emptyList()
        val element = delegation.defined.element as? Call ?: return emptyList()
        val head = delegation.defined.head
        if (head in crossed) return emptyList()
        val within = crossed + head
        val headArity = delegation.defined.declaration.arity
        val arity = headArity.arityInterval()?.maximum

        return inModule(at, target.module, target.function, arity, imports = true, within)
            .filter { it.defined.head !in within }
            .map {
                Reached(
                    delegation.defined.uncertain?.let(it.defined::marked) ?: it.defined,
                    if (delegation.reach.held && it.reach.remote) Reach.DELEGATION_TARGET else Reach.UNHELD_DELEGATION_TARGET,
                    delegation.paths + it.paths,
                    listOf(element) + it.delegations,
                    listOf(headArity) + it.heads,
                    delegation.through ?: head,
                    it.searched,
                    named && it.named,
                    delegation.imported && it.imported,
                    it.remote,
                )
            }
    }

    /** The env at the end of [result]'s module body, where the body was expanded to its end. */
    private fun bodyEnv(result: ExpansionResult): Env? =
        (result.units.firstOrNull { it.owner == ExpansionResult.Owner.ModuleBody }?.expansion as? Expansion.Expanded)?.env

    /** [module]'s declarations of [name], or of every name when it is `null`, from its copy that [at]'s file loads. */
    private fun ofModule(at: At, module: String, name: String?): List<Defined> =
        definedIn(at, at.holder.locate(at.file, module, at.inputs), module, name)

    /** [module]'s declarations of [name], or of every name when it is `null`, in [located], with the gap it meets. */
    private fun definedIn(at: At, located: ProjectModules.Located, module: String, name: String?): List<Defined> =
        when (located) {
            ProjectModules.Located.Unknown -> at.gap(Uncertain.NO_EXPANSION)
            ProjectModules.Located.None -> emptyList()
            is ProjectModules.Located.Several -> everyCopy(at, located, module, name)
            is ProjectModules.Located.Source -> known(at, located.result)?.let { defined(located.file, it, name) }.orEmpty()
            is ProjectModules.Located.Compiled -> compiled(at, located, name)
        }

    /**
     * Each of [several]'s copies' declarations of [name], each [Uncertain.SEVERAL], as which copy loads is unknown.
     * Copies that answer from one source give it once.
     */
    private fun everyCopy(at: At, several: ProjectModules.Located.Several, module: String, name: String?): List<Defined> {
        at.gap(Uncertain.SEVERAL)

        return at.holder.locateEach(at.file, several, module, at.inputs)
            .flatMap { copy -> definedIn(at, copy, module, name) }
            .distinctBy { it.declaredAt }
            .map { it.marked(Uncertain.SEVERAL) }
    }

    /**
     * The name index's definitions of [name] admitting [arity] in [file]'s scope, in each module [wanted] takes, each
     * [Uncertain.INDEX]: the public ones, and the private ones of each module [own] takes, the modules the use is in. One
     * for each module and name, as Elixir has one function of each among those admitting an arity, so a library's source
     * and its `.beam` give one.
     */
    private fun indexed(file: PsiFile, name: String, arity: Int?, own: (String) -> Boolean, wanted: (String) -> Boolean): List<Defined> =
        indexed(file, name)
            .filter { defined ->
                defined.declaration.name == name &&
                    defined.admits(arity) &&
                    wanted(defined.module) &&
                    (defined.declaration.visibility != Visibility.PRIVATE || own(defined.module))
            }
            .distinctBy { it.key }

    /**
     * What the name index defines [name] as in [file]'s resolve scope, held on [file] until a tracker of
     * [ProjectModules.copyTrackers] or any PSI change moves: a `.beam` rewritten in place moves only the PSI.
     */
    private fun indexed(file: PsiFile, name: String): List<Defined> {
        val project = file.project
        val byName = CachedValuesManager.getManager(project).getCachedValue(
            file,
            indexedByName,
            {
                CachedValueProvider.Result.create(
                    ConcurrentHashMap<String, List<Defined>>(),
                    *ProjectModules.getInstance(project).copyTrackers(),
                    PsiModificationTracker.MODIFICATION_COUNT,
                )
            },
            false,
        )

        return byName.getOrPut(name) {
            Probe.count(Probe.Counter.INDEX_QUERIES, 1)
            StubIndex.getElements(AllName.KEY, name, project, file.resolveScope, NamedElement::class.java)
                .flatMap { element ->
                    ProgressManager.checkCanceled()

                    indexedDefined(element)
                }
        }
    }

    /**
     * What [element] defines, in each module the module index keys its module under: a source definition's nearest
     * `defmodule`, `defimpl` or `defprotocol`, whose names it is indexed by, and none in any other, such as a `quote`. A
     * `defdelegate` is indexed by each of its heads.
     */
    private fun indexedDefined(element: NamedElement): List<Defined> =
        when (element) {
            is CallDefinition -> {
                val module = indexName(element.parent) ?: return emptyList()

                listOf(Defined(element.declaration(), element, element, module, null, uncertain = Uncertain.INDEX))
            }
            is Call if CallDefinitionClause.`is`(element) -> {
                val declaration = CallDefinitionClause.declaration(element, ResolveState.initial()) ?: return emptyList()

                indexedIn(element, Form.CLAUSE, declaration)
            }
            is Call -> {
                val delegation = CallDefinitionHead.enclosingDelegationCall(element) ?: return emptyList()
                val declaration = Delegation.declaration(delegation, element, ResolveState.initial()) ?: return emptyList()

                indexedIn(delegation, Form.DELEGATION, declaration)
            }
            else -> emptyList()
        }

    /** [declaration], which [declaring] makes as [form], in each module [declaring]'s nearest modular call is indexed under. */
    private fun indexedIn(declaring: Call, form: Form, declaration: Declaration): List<Defined> {
        val modules = CallDefinitionClause.enclosingModularMacroCall(declaring)?.let(::indexNames).orEmpty()
        if (modules.isEmpty()) return emptyList()
        val written = DeclaringForm.head(declaring, form, declaration.name, declaration.arity)

        return modules.map { module -> Defined(declaration, declaring, written, module, null, uncertain = Uncertain.INDEX) }
    }

    /** The names the module index keys [modular] under, where it is a `defmodule`, `defimpl` or `defprotocol`. */
    private fun indexNames(modular: Call): Collection<String> {
        val definition = definition(modular)?.takeIf { it.type == Definition.Type.MODULAR } ?: return emptySet()

        return Named.indexedNames(definition, SyntacticCall.of(modular).canonicalNameSet())
    }

    /** The `defmodule`, `defimpl` or `defprotocol` [call] is written in, past any clause; none inside a `quote`. */
    private fun ownModular(call: Call): Call? =
        generateSequence(CallDefinitionClause.enclosingModularMacroCall(call), CallDefinitionClause::enclosingModularMacroCall)
            .takeWhile { !QuoteMacro.`is`(it) }
            .firstOrNull { definition(it)?.type == Definition.Type.MODULAR }

    /**
     * [indexed]'s definitions that none of [found] admitting [arity] has the module and name of, each [Reach.INDEXED],
     * reached by a [remote] call or a local one.
     */
    private fun beside(
        found: List<Reached>,
        indexed: List<Defined>,
        name: String,
        arity: Int?,
        remote: Boolean,
    ): List<Reached> {
        val known = found.filter { it.defined.admits(arity) }.mapTo(HashSet()) { it.defined.key }

        return indexed
            .filter { it.key !in known }
            .map { reached(it, Reach.INDEXED, emptyList(), name, arity, remote) }
    }

    /** [result], which is a gap where there is none or what it defines isn't known. */
    private fun known(at: At, result: ExpansionResult?): ExpansionResult? {
        if (result == null) {
            at.gap(Uncertain.MODULE)

            return null
        }
        if (!result.definitionsKnown) at.gap(Uncertain.MODULE)

        return result
    }

    /**
     * [result]'s module's definitions of [name], or of every name when it is `null`, in [file]: one for each head, each
     * with its arities. A head a macro's output injected is declared where the macro writes it.
     */
    private fun defined(file: ElixirFile, result: ExpansionResult, name: String?): List<Defined> =
        NameIndex.of(result.table).named(name).flatMap { (nameArity, entry) ->
            val arity = if (entry.defaults == 0) {
                ArityKnowledge.Exact(nameArity.arity)
            } else {
                ArityKnowledge.Range(nameArity.arity - entry.defaults, nameArity.arity)
            }
            entry.heads.mapNotNull { head ->
                ProgressManager.checkCanceled()

                val injectedBy = head.injected?.call?.let { injectingCall(file, it) }
                val call = head.injected?.place?.let { placed(file, it) }
                    ?: declaringCall(file, head.at.meta.origin)
                    ?: return@mapNotNull null
                val stored = definer(entry.kind).capabilities
                // `super` saves an overridden public definition as a private one, with the head it was written with, so
                // the table gives the visibility and the written head the rest. A head placed at its macro's clause
                // has only the table's.
                val definer = CallDefinitionClause.definer(call)?.takeIf { declares(call, nameArity.name) }
                val capabilities = (definer?.capabilities ?: stored).copy(visibility = stored.visibility)
                val form = DeclaringForm.shapedForm(call) ?: Form.CLAUSE
                val declared = Declared.Source(form, CallDefinitionClause.sourceOrigin(call))
                // A head placed at its macro's clause, or at the call that injected it, is written by neither.
                val written = call.takeIf { form != Form.CLAUSE || definer != null }
                    ?.let { DeclaringForm.head(it, form, nameArity.name, arity) }

                Defined(
                    Declaration(nameArity.name, arity, capabilities, declared),
                    call,
                    written,
                    Module.indexName(result.module),
                    injectedBy,
                    head.delegation,
                )
            }
        }

    /**
     * [located]'s exports of [name], or of every name when it is `null`: each declared by the heads its attached source
     * writes for it, or else by its decompiled definition.
     */
    private fun compiled(at: At, located: ProjectModules.Located.Compiled, name: String?): List<Defined> {
        val exports = located.exports as? ModuleExports.Present ?: return at.gap(Uncertain.MODULE)
        val module = (PsiManager.getInstance(at.file.project).findFile(located.beam) as? PsiCompiledFile)
            ?.children
            ?.filterIsInstance<BeamModule>()
            ?.singleOrNull()
            ?: return at.gap(Uncertain.MODULE)
        val indexName = indexName(module) ?: return at.gap(Uncertain.MODULE)
        // Each is read only when needed: the decompiled definitions for an export no written head declares, and the
        // source's heads once an export is named, so a name the module doesn't export parses no source.
        val definitions by lazy(LazyThreadSafetyMode.NONE) { NameIndex.of(module) }
        val heads by lazy(LazyThreadSafetyMode.NONE) { located.source?.let { NameIndex.of(it, indexName) } }

        fun decompiled(nameArity: NameArity): Defined? {
            val definition = definitions.named(nameArity.name)
                .firstOrNull { nameArity.arity in it.nameArityInterval.arityInterval }
                ?: return null
            val origin = CompiledOrigin(located.beam, module.name, nameArity.name, nameArity.arity)

            return Defined(
                Declaration(nameArity.name, ArityKnowledge.Exact(nameArity.arity), definition.capabilities, Declared.Compiled(origin)),
                definition,
                definition,
                indexName,
                null,
            )
        }

        val exported = NameIndex.of(exports)

        return exported.named(name).flatMap { nameArity ->
            val macro = nameArity in exports.macros
            // Only a public head of the export's kind can write it.
            val written = heads?.named(nameArity.name).orEmpty().filter { head ->
                val capabilities = head.declaration.capabilities

                capabilities.public && capabilities.compileTime == macro && head.declaration.arity.admits(nameArity.arity)
            }

            if (written.isEmpty()) {
                listOfNotNull(decompiled(nameArity))
            } else {
                val arities = exported.named(nameArity.name).filter { (it in exports.macros) == macro }.mapTo(HashSet(), NameArity::arity)

                written.map { Defined(exportedAt(it.declaration, arities, nameArity.arity), it.call, it.written, indexName, null) }
            }
        }.distinctBy { it.declaredAt }
    }

    /**
     * [declaration] as written where [exported] holds every arity it admits; else at [arity] alone, as a splice or a
     * default in a branch that didn't run admits arities never exported.
     */
    private fun exportedAt(declaration: Declaration, exported: Set<Int>, arity: Int): Declaration {
        val listing = declaration.arity.listing()

        return if (listing.from == null && listing.arities.isNotEmpty() && exported.containsAll(listing.arities)) {
            declaration
        } else {
            declaration.copy(arity = ArityKnowledge.Exact(arity))
        }
    }

    /** The name the module index keys [module] under, from its stub. */
    private fun indexName(module: BeamModule): String? =
        ((module as? StubBasedPsiElement<*>)?.stub as? ModuleStub<*>)?.canonicalNameSet()?.singleOrNull()

    /** Whether an `import` of [arities] brings in [defined] at any of its arities, an unknown arity being any. */
    private fun bringsIn(defined: Defined, arities: List<Int>): Boolean {
        val interval = defined.declaration.arity.arityInterval() ?: ArityInterval(0)

        return arities.any { it in interval }
    }

    /** The module [qualifier] names, in the env at [at] where the expansion didn't reach it. */
    private fun receiver(at: At, qualifier: PsiElement): String? =
        EnvAtom.receiver(qualifier, at.receivers, at.env, at.lowering, at.inputs, at.holder)

    private fun found(reached: Reached, use: Use.OfCall, envUncertain: Uncertain?): Found? =
        applicability(reached, use)?.let { applicability ->
            Found(
                Candidate(reached.defined.declaration, reached.reach, applicability),
                reached.defined.element,
                reached.paths + reached.delegations,
                reached.defined.head,
                reached.through,
                reached.defined.uncertain ?: envUncertain,
            )
        }

    /**
     * As the walk decides it, where behind a delegation the name it calls is the name looked up, but a private
     * definition a remote call reaches is [Applicability.PRIVATE].
     */
    private fun applicability(reached: Reached, use: Use.OfCall): Applicability? {
        val declaration = reached.defined.declaration
        val applicability = when (use) {
            is Use.Named -> Applicability.of(declaration, reached.searched ?: use.name, reached.named, use.arity, reached.heads)
            is Use.AnyName ->
                Applicability.ofAnyName(declaration, reached.searched, reached.imported, use.arity, reached.heads)
        }

        return if (applicability == Applicability.VALID && reached.remote && declaration.visibility == Visibility.PRIVATE) {
            Applicability.PRIVATE
        } else {
            applicability
        }
    }

    /**
     * Whether [element] is a `def*` call of [name]: not the macro clause that debug info, which places nothing more
     * finely, places an injected head at.
     */
    private fun declares(element: PsiElement, name: String): Boolean =
        element is Call && CallDefinitionClause.`is`(element) &&
            CallDefinitionClause.nameArityInterval(element, ResolveState.initial())?.name == name

    private fun definer(kind: DefinitionTable.Kind): Definer =
        when (kind) {
            DefinitionTable.Kind.DEF -> Definer.DEF
            DefinitionTable.Kind.DEFP -> Definer.DEFP
            DefinitionTable.Kind.DEFMACRO -> Definer.DEFMACRO
            DefinitionTable.Kind.DEFMACROP -> Definer.DEFMACROP
        }

    /** The call written in [file] whose evaluated output [call] is, at its origin. */
    private fun injectingCall(file: PsiFile, call: ElixirAst.Call): Call? =
        call.meta.origin.let { origin -> enclosing(file, origin).firstOrNull { it.textRange == origin } }

    /**
     * The declaring call at [place]: in its macro's file, as [ProjectModules] reads that file, `null` where it is gone, or
     * in [expanded].
     */
    private fun placed(expanded: PsiFile, place: Place): Call? =
        (if (place.file == null) expanded else ProjectModules.getInstance(expanded.project).read(place.file))
            ?.let { declaringCall(it, place.origin) }

    /** The `import` call whose origin is [origin]: the innermost call covering it named `import`. */
    private fun importCall(file: PsiFile, origin: TextRange): Call? =
        enclosing(file, origin).firstOrNull { it.functionName() == "import" }

    /** The call whose head or whole the expander's node at [origin] is: the innermost covering it of a declaring form. */
    private fun declaringCall(file: PsiFile, origin: TextRange): Call? {
        val calls = enclosing(file, origin)

        return calls.firstOrNull { DeclaringForm.shapedForm(it) != null } ?: calls.firstOrNull()
    }

    /** The calls covering [origin] in [file], innermost first. */
    private fun enclosing(file: PsiFile, origin: TextRange): Sequence<Call> =
        generateSequence(file.findElementAt(origin.startOffset)) { element -> element.parent.takeIf { element !is PsiFile } }
            .filterIsInstance<Call>()
            .filter { it.textRange.contains(origin) }

    /**
     * Counts of the reads one answer makes through a name index or the env index, for the tests that pin them as
     * independent of a module's size.
     */
    object Probe {
        enum class Counter {
            /** Entries read into a name index. */
            INDEXED,

            /** Entries a lookup examines. */
            EXAMINED,

            /** Queries of the name index. */
            INDEX_QUERIES,
        }

        @Volatile
        private var counting = false

        private val counts = EnumMap<Counter, Long>(Counter::class.java)

        fun count(counter: Counter, n: Int) {
            if (counting) {
                synchronized(counts) { counts.merge(counter, n.toLong(), Long::plus) }
            }
        }

        @TestOnly
        fun <T> counting(block: () -> T): T {
            counting = true

            return try {
                block()
            } finally {
                counting = false
            }
        }

        @TestOnly
        fun snapshot(): Map<Counter, Long> = synchronized(counts) { Counter.entries.associateWith { counts[it] ?: 0L } }

        @TestOnly
        fun reset() = synchronized(counts) { counts.clear() }
    }

    private const val KERNEL = "Elixir.Kernel"
    private const val SPECIAL_FORMS = "Elixir.Kernel.SpecialForms"
}
