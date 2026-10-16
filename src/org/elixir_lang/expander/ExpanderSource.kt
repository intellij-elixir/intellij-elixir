package org.elixir_lang.expander

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiCompiledFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.call.Visibility
import org.elixir_lang.code_insight.eachNameOnce
import org.elixir_lang.declaration.Applicability
import org.elixir_lang.declaration.ArityKnowledge
import org.elixir_lang.declaration.Candidate
import org.elixir_lang.declaration.CandidateSource
import org.elixir_lang.declaration.CompiledOrigin
import org.elixir_lang.declaration.Declaration
import org.elixir_lang.declaration.Declared
import org.elixir_lang.declaration.Definer
import org.elixir_lang.declaration.Form
import org.elixir_lang.declaration.Found
import org.elixir_lang.declaration.Reach
import org.elixir_lang.declaration.Use
import org.elixir_lang.declaration.Visible
import org.elixir_lang.declaration.admits
import org.elixir_lang.lowering.Lowering
import org.elixir_lang.psi.ArityInterval
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.Import.Term
import org.elixir_lang.psi.arityInterval
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.qualification.Qualified
import org.elixir_lang.psi.impl.stripAccessExpression
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
            is Use.Specified, is Use.Remote -> emptyList()
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
            val module = receiver(at, call) ?: return emptyList()
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
     * after a qualifier, the public definitions of the module it names, as completion offers them ([eachNameOnce]).
     */
    @RequiresReadLock
    override fun visible(position: Call): List<Visible> {
        ThreadingAssertions.assertReadAccess()

        val at = inputs(position)?.let { at(position, it) } ?: return emptyList()
        val defined = if (position is Qualified) {
            val module = receiver(at, position) ?: return emptyList()
            eachNameOnce(ofModule(at, module, null).filter { it.declaration.visibility != Visibility.PRIVATE })
        } else {
            buildList {
                addAll(ownDefined(at, null))
                importedDefined(at, at.file, at.env, null).mapTo(this) { it.defined }
                addAll(specialFormsDefined(at, null))
            }
        }

        return defined.map { Visible(it.declaration.name, it.declaration.name, it.declaration, it.element) }
    }

    /**
     * [defined] as completion offers a module's functions: of a source module's, the preferred head of each name its
     * clauses define, then one `defdelegate` for each other name; each of a `.beam`'s.
     */
    private fun eachNameOnce(defined: List<Defined>): List<Defined> {
        val (source, compiled) = defined.partition { it.declaration.declared is Declared.Source }
        val (delegations, clauses) = source.partition { (it.declaration.declared as Declared.Source).form == Form.DELEGATION }
        val byElement = clauses.associateBy { it.element }
        val calls = clauses.mapNotNull { it.element as? Call }.filter { CallDefinitionClause.`is`(it) }

        return eachNameOnce(calls, delegations.map { it.declaration.name to it }) { _, clause -> byElement.getValue(clause) } +
            compiled
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
    private fun inputs(call: Call): ProjectModules.Inputs? {
        val file = call.containingFile as? ElixirFile ?: return null

        return (SdkInputs.getInstance(file.project).inputsFor(file) as? SdkInputs.Answer.Ready)?.inputs
    }

    /** `null` in dumb mode. */
    private fun at(call: Call, inputs: ProjectModules.Inputs): At? {
        val file = call.containingFile as? ElixirFile ?: return null
        if (DumbService.isDumb(file.project)) return null
        val holder = ProjectModules.getInstance(file.project)

        val indexed = holder.indexed(file, inputs)

        return At(file, call.textRange, inputs, indexed.envs.at(call.textRange), holder, indexed.receivers, indexed.lowering)
    }

    /**
     * A declaration, the PSI that declares it, and the delegation its head makes.
     *
     * @property headArity the arity the head declares, which an `import` narrowing [declaration]'s leaves as it is
     */
    private class Defined(
        val declaration: Declaration,
        val element: PsiElement,
        val delegation: DefinitionTable.Delegation? = null,
        val headArity: ArityKnowledge = declaration.arity,
    ) {
        val head: Head get() = Head(element, declaration.name, headArity)

        /** Whether [declaration] takes [arity] arguments, which is not known past a head with no largest arity. */
        fun admits(arity: Int?): Boolean = arity == null || declaration.arity.admits(arity)
    }

    /** One head of a definition: the PSI that declares it, which a list of `defdelegate` heads shares, and its name and arity. */
    private data class Head(val element: PsiElement, val name: String, val headArity: ArityKnowledge)

    /**
     * A declaration reached for a use.
     *
     * @property paths the `import` calls crossed, outermost first
     * @property delegations the `defdelegate` calls crossed, from the use outward
     * @property heads the arity each of [delegations] declares in its head
     * @property searched the name looked up where it was found
     * @property named whether everything but the arity fits through every head crossed: each name, and each `import`
     *   that leaves out some of a definition's arities bringing it in at the arity reaching it
     * @property remote whether a remote call reaches it, which a private definition refuses
     */
    private class Reached(
        val defined: Defined,
        val reach: Reach,
        val paths: List<Call>,
        val delegations: List<Call>,
        val heads: List<ArityKnowledge>,
        val searched: String?,
        val named: Boolean,
        val remote: Boolean,
    )

    private fun own(at: At, name: String?, arity: Int): List<Reached> =
        ownDefined(at, name).flatMap {
            reachedFrom(at, it, Reach.OWN, emptyList(), name, arity, remote = false, emptySet())
        }

    /** The definitions of [name], or of every name when it is `null`, in the module body [at] is in. */
    private fun ownDefined(at: At, name: String?): List<Defined> {
        val module = at.env.module ?: return emptyList()
        val result = ProjectModules.results(at.holder.expansion(at.file, at.inputs)).firstOrNull { result ->
            result.module == module &&
                result.units.any { it.owner == ExpansionResult.Owner.ModuleBody && it.node.meta.origin.contains(at.range) }
        } ?: return emptyList()

        return defined(at.file, result.table, name)
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
        importedDefined(at, file, env, name)
            .groupBy { ImportedHead(it.defined.head, it.call) }
            .values
            .flatMap { runs ->
                val reached = runs.map { (defined, reach, call) -> reached(defined, reach, listOfNotNull(call), name, arity, remote = false) }

                // The runs of one head share its delegation, which is followed once.
                reached + throughDelegation(
                    at,
                    reached.first(),
                    reached.any(Reached::named),
                    runs.any { it.defined.admits(arity) },
                    crossed,
                )
            }

    /** A definition an import brings in, how, and the `import` call that brings it in, if any. */
    private data class Imported(val defined: Defined, val reach: Reach, val call: Call?)

    /** One head an `import` call brings in, whose runs of arities share its delegation. */
    private data class ImportedHead(val head: Head, val call: Call?)

    /** The definitions [env]'s imports bring in of [name], or of every name when it is `null`. */
    private fun importedDefined(at: At, file: ElixirFile, env: Env, name: String?): List<Imported> =
        (env.functions + env.macros).flatMap { imports ->
            val aritiesByName = NameIndex.of(imports).named(name).groupBy({ it.name }, { it.arity })
            if (aritiesByName.isEmpty()) return@flatMap emptyList()
            val call = imports.origin?.let { importCall(file, it) }
            // The `Kernel` import a `defprotocol` builds has the `defprotocol`'s origin, and no `import` call names it.
            val reach = if (call == null && imports.module == KERNEL) Reach.IMPLICIT_IMPORT else Reach.IMPORT

            aritiesByName.flatMap { (imported, arities) ->
                ofModule(at, imports.module, imported).flatMap { narrowed(it, arities.toSet()) }.map { Imported(it, reach, call) }
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

            Defined(defined.declaration.copy(arity = arity), defined.element, defined.delegation, arity)
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
    ): List<Reached> =
        when (val located = at.holder.locate(at.file, module, at.inputs)) {
            ProjectModules.Located.Unknown, ProjectModules.Located.None, is ProjectModules.Located.Several -> emptyList()
            is ProjectModules.Located.Source -> {
                val result = located.result ?: return emptyList()
                val own = defined(located.file, result.table, name)
                    .flatMap { reachedFrom(at, it, Reach.OWN, emptyList(), name, arity, remote = true, crossed) }
                val body = if (imports) bodyEnv(result) else null

                own + body?.let { imported(at, located.file, it, name, arity, crossed) }.orEmpty()
            }
            is ProjectModules.Located.Compiled ->
                compiled(at.file, located, name)
                    .flatMap { reachedFrom(at, it, Reach.OWN, emptyList(), name, arity, remote = true, crossed) }
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
        val reached = reached(defined, reach, paths, name, arity, remote)

        return listOf(reached) + throughDelegation(at, reached, reached.named, defined.admits(arity), crossed)
    }

    /** [defined], looked up as [name] at [arity], which is `null` past a head with no largest arity. */
    private fun reached(defined: Defined, reach: Reach, paths: List<Call>, name: String?, arity: Int?, remote: Boolean): Reached {
        val declaration = defined.declaration
        // An `import` that leaves out some of a head's arities brings in only the rest.
        val admitted = declaration.arity == defined.headArity || defined.admits(arity)
        val named = name != null && declaration.name == name && admitted

        return Reached(defined, reach, paths, emptyList(), emptyList(), name, named, remote)
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
        val arity = delegation.defined.headArity.arityInterval()?.maximum

        return inModule(at, target.module, target.function, arity, imports = true, within)
            .filter { it.defined.head !in within }
            .map {
                Reached(
                    it.defined,
                    if (delegation.reach.held && it.reach.remote) Reach.DELEGATION_TARGET else Reach.UNHELD_DELEGATION_TARGET,
                    delegation.paths + it.paths,
                    listOf(element) + it.delegations,
                    listOf(delegation.defined.headArity) + it.heads,
                    it.searched,
                    named && it.named,
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
            is ProjectModules.Located.Source -> located.result?.let { defined(located.file, it.table, name) }.orEmpty()
            is ProjectModules.Located.Compiled -> compiled(at.file, located, name)
        }

    /** [table]'s definitions of [name], or of every name when it is `null`: one for each head, each with its arities. */
    private fun defined(file: ElixirFile, table: DefinitionTable, name: String?): List<Defined> =
        NameIndex.of(table).named(name).flatMap { (nameArity, entry) ->
            val arity = if (entry.defaults == 0) {
                ArityKnowledge.Exact(nameArity.arity)
            } else {
                ArityKnowledge.Range(nameArity.arity - entry.defaults, nameArity.arity)
            }

            entry.heads.mapNotNull { head ->
                val call = declaringCall(file, head.at.meta.origin) ?: return@mapNotNull null
                val stored = definer(entry.kind).capabilities
                // `super` saves an overridden public definition as a private one, with the head it was written with, so
                // the table gives the visibility and the written head the rest.
                val capabilities = (CallDefinitionClause.definer(call)?.capabilities ?: stored).copy(visibility = stored.visibility)
                val form = DeclaringForm.shapedForm(call) ?: Form.CLAUSE
                val declared = Declared.Source(form, CallDefinitionClause.sourceOrigin(call))

                Defined(Declaration(nameArity.name, arity, capabilities, declared), call, head.delegation)
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
            )
        }
    }

    /** [defined] at only the arities an `import` brings in, one for each run of them. */
    private fun narrowed(defined: Defined, arities: Set<Int>): List<Defined> {
        val declaration = defined.declaration
        // An unknown arity may be any.
        val interval = declaration.arity.arityInterval() ?: ArityInterval(0)
        val kept = arities.filter { it in interval }.sorted()
        if (interval.maximum?.let { kept.size == it - interval.minimum + 1 } == true) return listOf(defined)

        return runs(kept).map { run ->
            val arity = if (run.first == run.last) ArityKnowledge.Exact(run.first) else ArityKnowledge.Range(run.first, run.last)

            Defined(declaration.copy(arity = arity), defined.element, defined.delegation, defined.headArity)
        }
    }

    private fun runs(sorted: List<Int>): List<IntRange> =
        sorted.fold(emptyList()) { runs, arity ->
            val last = runs.lastOrNull()
            if (last != null && last.last + 1 == arity) runs.dropLast(1) + listOf(last.first..arity) else runs + listOf(arity..arity)
        }

    /**
     * The module [call]'s qualifier expanded to in its file's expansion, or where the expansion didn't reach it, expands
     * to in the env at [call]; `null` where it expands to no atom.
     */
    private fun receiver(at: At, call: Qualified): String? {
        val qualifier = call.qualifier().stripAccessExpression()

        return when (val receiver = at.receivers.at(qualifier.textRange)) {
            is ReceiverIndex.Receiver.Module -> receiver.name
            ReceiverIndex.Receiver.Unknown -> null
            ReceiverIndex.Receiver.Unreached -> {
                val expansion = Expander.expand(
                    at.lowering.lower(qualifier),
                    ExState.empty(at.inputs.level),
                    at.env,
                    at.inputs.level,
                    { at.holder.exports(at.file, it, at.inputs) },
                    { at.holder.struct(at.file, it, at.inputs) },
                )

                ((expansion as? Expansion.Expanded)?.value as? Term.Atom)?.name
            }
        }
    }

    private fun found(reached: Reached, use: Use.OfCall): Found? =
        applicability(reached, use)?.let { applicability ->
            Found(
                Candidate(reached.defined.declaration, reached.reach, applicability),
                reached.defined.element,
                reached.paths + reached.delegations,
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
            is Use.AnyName -> Applicability.ofAnyName(declaration, reached.searched, use.arity, reached.heads)
        }

        return if (applicability == Applicability.VALID && reached.remote && declaration.visibility == Visibility.PRIVATE) {
            Applicability.PRIVATE
        } else {
            applicability
        }
    }

    private fun definer(kind: DefinitionTable.Kind): Definer =
        when (kind) {
            DefinitionTable.Kind.DEF -> Definer.DEF
            DefinitionTable.Kind.DEFP -> Definer.DEFP
            DefinitionTable.Kind.DEFMACRO -> Definer.DEFMACRO
            DefinitionTable.Kind.DEFMACROP -> Definer.DEFMACROP
        }

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
