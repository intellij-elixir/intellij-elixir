package org.elixir_lang.expander

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiCompiledFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.ResolveState
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.call.Visibility
import org.elixir_lang.code_insight.eachNameOnce
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
import org.elixir_lang.declaration.Use
import org.elixir_lang.declaration.Visible
import org.elixir_lang.declaration.importedArities
import org.elixir_lang.declaration.admits
import org.elixir_lang.Module
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Lowering
import org.elixir_lang.psi.ArityInterval
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.arityInterval
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.qualification.Qualified
import org.elixir_lang.psi.scope.call_definition_clause.DeclaringForm
import org.jetbrains.annotations.TestOnly
import java.util.EnumMap
import org.elixir_lang.beam.psi.Module as BeamModule

/**
 * Candidates from the expansion: the env each use was expanded in, the definition table of the module it is in, and
 * each module's copy as Elixir loads it.
 */
internal object ExpanderSource : CandidateSource {
    @RequiresReadLock
    override fun candidates(use: Use, incompleteCode: Boolean): List<Found> {
        ThreadingAssertions.assertReadAccess()

        return when (use) {
            is Use.OfCall -> inputs(use.entrance)?.let { answer(use, it) }.orEmpty()
            is Use.Specified, is Use.Remote, is Use.Tag, is Use.Link -> emptyList()
        }
    }

    /** [use]'s candidates, its file expanded with [inputs] in place of its SDK's. */
    @TestOnly
    @RequiresReadLock
    internal fun candidates(use: Use.OfCall, inputs: ProjectModules.Inputs): List<Found> {
        ThreadingAssertions.assertReadAccess()

        return answer(use, inputs)
    }

    private fun answer(use: Use.OfCall, inputs: ProjectModules.Inputs): List<Found> {
        val call = use.entrance
        val at = at(call, inputs) ?: return emptyList()
        val name = (use as? Use.Named)?.name
        val reached = if (call is Qualified) {
            val module = receiver(at, call.qualifier()) ?: return emptyList()
            inModule(at, module, name, use.arity, imports = false, emptySet())
        } else {
            buildList {
                addAll(own(at, name, use.arity))
                addAll(imported(at, at.file, at.env, name, use.arity, emptySet()))
                addAll(specialForms(at, name, use.arity))
            }
        }

        return reached.mapNotNull { found(it, use) }
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
        ).map { visible(it, null) }
    }

    private fun visibleAt(position: Call, inputs: ProjectModules.Inputs): List<Visible> {
        val at = at(position, inputs) ?: return emptyList()

        return buildList {
            ownDefined(at, null).mapTo(this) { visible(it, null) }
            importedDefined(at, at.file, at.env, null).mapTo(this) { (defined, _, _, arities) ->
                visible(defined, importedArities(defined.declaration.arity) { it in arities })
            }
            specialFormsDefined(at, null).mapTo(this) { visible(it, null) }
        }
    }

    private fun visible(defined: Defined, importedArities: Set<Int>?): Visible =
        Visible(defined.declaration.name, defined.declaration.name, defined.declaration, defined.element, importedArities)

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
     * Where a use is, as its file's expansion saw it, what each receiver it reached expanded to, and the [lowering] it
     * read the file through.
     */
    private class At(
        val file: ElixirFile,
        val range: TextRange,
        val inputs: ProjectModules.Inputs,
        val env: Env,
        val holder: ProjectModules,
        val receivers: ReceiverIndex,
        val lowering: Lowering,
    )

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

        return At(file, element.textRange, inputs, indexed.envs.at(element.textRange), holder, indexed.receivers, indexed.lowering)
    }

    /**
     * A declaration, the PSI that declares it, the module whose definitions it is, and the delegation its head makes.
     *
     * @property written the head written for it, as [Head.written] is, `null` where nothing written declares it
     * @property module the module's index name (`Module.indexName`), as [Head.module] has it
     * @property injectedBy the call whose evaluated output injected it into [module], as written in [module]'s file
     */
    private class Defined(
        val declaration: Declaration,
        val element: PsiElement,
        val written: PsiElement?,
        val module: String,
        val injectedBy: Call?,
        val delegation: DefinitionTable.Delegation? = null,
    ) {
        /** Its head, which tells apart the heads of a list of `defdelegate` heads, and the modules one macro's output declares it in. */
        val head: Head get() = Head(written, module, declaration.name, declaration.arity)

        /** Whether [declaration] takes [arity] arguments, which is not known past a head with no largest arity. */
        fun admits(arity: Int?): Boolean = arity == null || declaration.arity.admits(arity)
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

    private fun own(at: At, name: String?, arity: Int): List<Reached> =
        ownDefined(at, name).flatMap { held(at, it, name, arity, remote = false, emptySet()) }

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
        } ?: return emptyList()

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

        return when (val located = at.holder.locate(at.file, module, at.inputs)) {
            ProjectModules.Located.Unknown, ProjectModules.Located.None, is ProjectModules.Located.Several -> emptyList()
            is ProjectModules.Located.Source -> {
                val result = located.result ?: return emptyList()
                val own = defined(located.file, result, name).flatMap { held(at, it, name, arity, remote = true, crossed) }
                val body = if (imports) bodyEnv(result) else null

                own + body?.let { imported(at, located.file, it, name, arity, crossed) }.orEmpty()
            }
            is ProjectModules.Located.Compiled ->
                compiled(at.file, located, name).flatMap { held(at, it, name, arity, remote = true, crossed) }
        }
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
     * it [admits] the arity that delegation calls it at, as no other head runs.
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
                    it.defined,
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
        when (val located = at.holder.locate(at.file, module, at.inputs)) {
            ProjectModules.Located.Unknown, ProjectModules.Located.None, is ProjectModules.Located.Several -> emptyList()
            is ProjectModules.Located.Source -> located.result?.let { defined(located.file, it, name) }.orEmpty()
            is ProjectModules.Located.Compiled -> compiled(at.file, located, name)
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

    /** [located]'s exports of [name], or of every name when it is `null`, each declared by its decompiled definition. */
    private fun compiled(asker: PsiFile, located: ProjectModules.Located.Compiled, name: String?): List<Defined> {
        val exports = located.exports as? ModuleExports.Present ?: return emptyList()
        val module = (PsiManager.getInstance(asker.project).findFile(located.beam) as? PsiCompiledFile)
            ?.children
            ?.filterIsInstance<BeamModule>()
            ?.singleOrNull()
            ?: return emptyList()
        val definitions = NameIndex.of(module)

        return NameIndex.of(exports).named(name).mapNotNull { nameArity ->
            val definition = definitions.named(nameArity.name)
                .firstOrNull { nameArity.arity in it.nameArityInterval.arityInterval }
                ?: return@mapNotNull null
            val origin = CompiledOrigin(located.beam, module.name, nameArity.name, nameArity.arity)

            Defined(
                Declaration(nameArity.name, ArityKnowledge.Exact(nameArity.arity), definition.capabilities, Declared.Compiled(origin)),
                definition,
                definition,
                origin.module,
                null,
            )
        }
    }

    /** Whether an `import` of [arities] brings in [defined] at any of its arities, an unknown arity being any. */
    private fun bringsIn(defined: Defined, arities: List<Int>): Boolean {
        val interval = defined.declaration.arity.arityInterval() ?: ArityInterval(0)

        return arities.any { it in interval }
    }

    /** The module [qualifier] names, in the env at [at] where the expansion didn't reach it. */
    private fun receiver(at: At, qualifier: PsiElement): String? =
        EnvAtom.receiver(qualifier, at.receivers, at.env, at.lowering, at.inputs, at.holder)

    private fun found(reached: Reached, use: Use.OfCall): Found? =
        applicability(reached, use)?.let { applicability ->
            Found(
                Candidate(reached.defined.declaration, reached.reach, applicability),
                reached.defined.element,
                reached.paths + reached.delegations,
                reached.defined.head,
                reached.through,
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
