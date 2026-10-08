package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.expander.ExpansionResult.Ended
import org.elixir_lang.expander.ExpansionResult.Owner
import org.elixir_lang.language_level.ElixirLanguageFeature.BOOLEAN_AND_NIL_MODULES_RESERVED
import org.elixir_lang.language_level.ElixirLanguageFeature.MODULE_NAME_REJECTS_SLASHES
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Meta
import org.elixir_lang.psi.Import.Term
import java.util.Collections
import java.util.IdentityHashMap

/**
 * A module whose body or definitions are being expanded: what it has defined so far, its local calls, and its
 * attributes.
 */
internal class Compiling(body: ElixirAst, level: ElixirLanguageLevel) {
    val table = DefinitionTable()

    /** Each definition's local calls, in expansion order. */
    val calls = LinkedHashMap<NameArity, MutableList<LocalCall<ElixirAst>>>()

    /** The private macros dispatched as local macros, in the order they were first dispatched. */
    val usedPrivate = LinkedHashSet<NameArity>()

    /** Each name and arity a function body dispatched through an import of another module, to that module. */
    val imports = LinkedHashMap<NameArity, String>()

    /** `Module.make_overridable/2`'s records, by the definition made overridable. */
    val overridable = LinkedHashMap<NameArity, Overridable>()

    /** The attributes at the point the module body has run to. */
    val attributes = AttributeTable(level)

    val effects = mutableListOf<AttributeLog.Logged>()

    val reads = mutableListOf<AttributeLog.Read>()

    /** The reads of the definition being stored, which take its owner once it is. */
    private val definitionReads = mutableListOf<Pair<String, Pair<ElixirAst, Term>>>()

    /** The struct `defstruct` recorded, if it has run. */
    var struct: ModuleStruct? = null

    /**
     * The `bind_quoted` values of the macro whose output is expanding, by variable name, in `Kernel`'s context: what
     * `defexception` gives `defstruct` to evaluate as the fields it builds on.
     */
    var bound: Map<String, ElixirAst> = emptyMap()

    val definitions = LinkedHashMap<NameArity, AttributeLog.DefinitionAttributes>()

    val unnamed = mutableListOf<AttributeLog.DefinitionAttributes>()

    private val statements: MutableSet<ElixirAst> = Collections.newSetFromMap(IdentityHashMap())

    /** Each call `@` built, to the `@`. */
    private val built = IdentityHashMap<ElixirAst, ElixirAst>()

    init {
        addStatements(body)
    }

    /** Whether [node] is a statement of the module body, or of a block that is one. */
    fun isStatement(node: ElixirAst): Boolean = node in statements

    /** [rewrite], a macro's output in place of [call], whose statements are statements when [call] is one. */
    fun rewrote(call: ElixirAst, rewrite: ElixirAst) {
        if (isStatement(call)) addStatements(rewrite)
    }

    /** [call] was built by [at], an `@`. */
    fun built(call: ElixirAst, at: ElixirAst) {
        built[call] = at
    }

    /** Where [call] is in the source: the `@` that built it, or itself. */
    fun site(call: ElixirAst): ElixirAst = built[call] ?: call

    /** `@name` at [at], in the definition being stored, injected [value]. */
    fun read(name: String, at: ElixirAst, value: Term) {
        definitionReads += name to (at to value)
    }

    /** The reads of the definition just stored, logged with its [owner]. */
    fun storedReads(owner: Owner) {
        definitionReads.mapTo(reads) { (name, read) -> AttributeLog.Read(name, read.first, owner, read.second) }
        definitionReads.clear()
    }

    val log: AttributeLog
        get() = AttributeLog(effects, reads, attributes.final, definitions, unnamed, attributes.accumulating)

    /** A block's expressions are statements, and so is what a statement that is a match binds, which runs where it does. */
    private fun addStatements(node: ElixirAst) {
        if (node is ElixirAst.Block) {
            node.expressions.forEach(::addStatements)
        } else {
            statements += node

            if (isCall(node, "=", 2)) addStatements((node as ElixirAst.Call).arguments!![1])
        }
    }
}

/** What a body defines, which Elixir evaluates once the body is expanded. */
internal sealed interface Pending {
    /**
     * A `def*` call, with its unquote fragments replaced by their values.
     *
     * @property node the `def*` call
     * @property head the call and guard being defined
     * @property body the options, or `null` for a bodiless head
     * @property unnamedAt the name's unquote fragment, when its value isn't an atom
     * @property stop the first other fragment whose value isn't a literal
     * @property env the env at [node]
     * @property ordered whether [node] is a statement of the module body, and named
     * @property statement whether [node] is a statement of the module body
     * @property checksClauses whether Elixir checks the definition for clauses: it has no unquotes, and its head
     *   wasn't quoted
     */
    class Definition(
        val kind: DefinitionTable.Kind,
        val node: ElixirAst.Call,
        val head: ElixirAst,
        val body: ElixirAst?,
        val unnamedAt: ElixirAst?,
        val stop: ElixirAst?,
        val env: Env,
        val ordered: Boolean,
        val statement: Boolean,
        val checksClauses: Boolean,
    ) : Pending

    /**
     * What a macro does to its module's table when the body runs, at [node]'s place among the definitions.
     *
     * @property ordered whether [node] is a statement of the module body
     * @property queued what runs after the effect, which depends on what it did
     * @property apply does it, and gives `null`, or the error the body raises there, or the [Expansion.Unported] where
     *   the effect can't be followed
     */
    class Effect(
        val node: ElixirAst,
        val ordered: Boolean,
        val queued: () -> List<Pending> = { emptyList() },
        val apply: (Compiling) -> Expansion?,
    ) : Pending

    /**
     * A module.
     *
     * @property node the `defmodule` call
     * @property name the module's name as atom text, or, where it isn't an atom, as `inspect/1` shows it
     * @property isAtom whether the name is an atom
     * @property env the env its body starts from
     * @property state the state its body starts from
     */
    class Module(
        val node: ElixirAst.Call,
        val name: String,
        val isAtom: Boolean,
        val body: ElixirAst,
        val env: Env,
        val state: ExState,
    ) : Pending

    /**
     * What a call in the module body does to the module's attributes.
     *
     * @property at the `@` that built the call, or the call
     * @property statement whether [at] is a statement of the module body
     */
    class Attribute(val effect: org.elixir_lang.expander.Effect, val at: ElixirAst, val statement: Boolean) : Pending
}

/** Whether the entry's node is a statement of the module body. A nested module is the body's own. */
internal fun Pending.isStatement(): Boolean =
    when (this) {
        is Pending.Definition -> statement
        is Pending.Effect -> ordered
        is Pending.Attribute -> statement
        is Pending.Module -> true
    }

/** The entry as it is when its node is a statement of the module body. */
internal fun Pending.asStatement(): Pending =
    when (this) {
        is Pending.Definition ->
            Pending.Definition(kind, node, head, body, unnamedAt, stop, env, unnamedAt == null, true, checksClauses)
        is Pending.Effect -> Pending.Effect(node, true, queued, apply)
        is Pending.Attribute -> Pending.Attribute(effect, at, true)
        is Pending.Module -> this
    }

/** [replacement] in place of what [Run.pending] gained since it had [from] entries, which a macro's own output queued. */
internal fun Run.replacePending(from: Int, replacement: List<Pending>) {
    pending.subList(from, pending.size).clear()
    pending += replacement
}

/** Each module [run]'s expansion so far defined, compiled in turn until one raises. */
internal fun compilePending(run: Run): List<ExpansionResult> {
    val modules = mutableListOf<ExpansionResult>()

    for (pending in run.pending) {
        if (pending !is Pending.Module) continue

        val result = compileModule(pending, run)

        modules += result

        if (result.ended.raises) break
    }

    return modules
}

/**
 * `elixir_module:compile`: [module]'s name checked, its body expanded, then each definition and nested module it
 * defines in the order Elixir evaluates the body, then the checks of its local calls.
 */
internal fun compileModule(module: Pending.Module, run: Run): ExpansionResult {
    val name = module.name
    val compiling = Compiling(module.body, run.level)

    nameError(name, module.isAtom, run)?.let { kind ->
        return ExpansionResult(
            name, compiling.table, emptyList(), emptyList(), Ended.Raised(Expansion.Error(kind, module.node)),
            emptyList(), emptySet(), emptyList(), AttributeLog.EMPTY,
        )
    }

    val enclosing = run.compiling.put(name, compiling)
    val enclosingPending = run.pending
    val consultedFrom = run.consulted.size
    val errors = mutableListOf<Reported>()
    var errorsFrom = run.errors.size
    val units = mutableListOf<ExpansionResult.Unit>()
    val nested = mutableListOf<ExpansionResult>()
    val effectOpaque = mutableListOf<Expansion.Opaque>()

    run.pending = mutableListOf()

    val body = Expander.expand(module.body, module.state, module.env, run)
    var ended = ended(body, run)

    units += ExpansionResult.Unit(Owner.ModuleBody, module.body, true, body)

    if (ended?.raises != true) {
        var ordered = true
        val queue = ArrayDeque(run.pending)

        while (queue.isNotEmpty()) {
            val unitEnded = when (val pending = queue.removeFirst()) {
                is Pending.Definition -> {
                    ordered = ordered && pending.ordered

                    val (owner, expansion) = storeDefinition(pending, compiling, run)

                    compiling.storedReads(owner)

                    if (expansion !is Expansion.Error) {
                        takeDefinitionAttributes(pending, owner as Owner.Definition, compiling)
                    }

                    units += ExpansionResult.Unit(owner, pending.node, ordered, expansion)
                    ended(expansion, run)
                }
                is Pending.Attribute -> {
                    compiling.effects += AttributeLog.Logged(pending.effect, pending.at, pending.statement)

                    when (val outcome = compiling.attributes.apply(pending.effect, pending.statement)) {
                        is EffectOutcome.Raises -> Ended.Raised(Expansion.Error(outcome.kind, pending.at))
                        // Whether Elixir raises there isn't known, so nothing after it can be compared.
                        EffectOutcome.Unchecked -> Ended.Stopped(pending.at)
                        EffectOutcome.Stored -> null
                    }
                }
                is Pending.Effect -> {
                    ordered = ordered && pending.ordered

                    val applied = pending.apply(compiling)

                    (applied as? Expansion.Opaque)?.let(effectOpaque::add)
                    applied?.let { ended(it, run) }.also { queue.addAll(0, pending.queued()) }
                }
                is Pending.Module -> {
                    errors += run.errors.subList(errorsFrom, run.errors.size)

                    val result = compileModule(pending, run)

                    errorsFrom = run.errors.size
                    nested += result
                    when (val inner = result.ended) {
                        Ended.Compiled -> null
                        // `elixir_module:compile/6` raises once the nested module's checks have run.
                        Ended.Tainted -> Ended.Raised(Expansion.Error("compile_error", pending.node))
                        is Ended.Raised, is Ended.Crashed, is Ended.Stopped -> inner
                    }
                }
            }

            ended = ended ?: unitEnded

            // A unit that raises ends the module body's evaluation, even after an earlier unit stopped.
            if (unitEnded?.raises == true) break
        }

        if (ended == null) storeNotOverridden(compiling)?.let { ended = Ended.Raised(it) }
    }

    if (ended == null) ended = beforeCompile(module, compiling, (body as Expansion.Expanded).env, run)

    // `eval_form/7` stores them again after the callbacks, which may have defined the other kind.
    if (ended == null) ended = storeNotOverridden(compiling)?.let { Ended.Raised(it) }

    if (ended == null) {
        ended = postModule(module, compiling, tainted = run.errors.size > errorsFrom || errors.isNotEmpty(), run)
    }

    errors += run.errors.subList(errorsFrom, run.errors.size)

    run.pending = enclosingPending

    if (enclosing == null) run.compiling.remove(name) else run.compiling[name] = enclosing

    val result = ExpansionResult(
        name,
        compiling.table,
        units,
        errors,
        ended ?: if (errors.isEmpty()) Ended.Compiled else Ended.Tainted,
        units.mapNotNull { it.expansion as? Expansion.Opaque } + effectOpaque,
        run.consulted.subList(consultedFrom, run.consulted.size).toSet(),
        nested,
        compiling.log,
    )

    run.load(result, compiling.struct)

    return result
}

/**
 * `elixir_module`'s `validate_module_name/1`, `check_module_availability/3`, then the code server's refusal of a module
 * being defined: the error [name] raises before the body runs, if any.
 */
private fun nameError(name: String, isAtom: Boolean, run: Run): String? {
    val level = run.level

    return when {
        !isAtom || name == "nil" || name == "true" || name == "false" -> "invalid_module_name"
        MODULE_NAME_REJECTS_SLASHES.isSufficient(level) && ('/' in name || '\\' in name) -> "invalid_module_name"
        name in RESERVED_MODULES -> "module_reserved"
        BOOLEAN_AND_NIL_MODULES_RESERVED.isSufficient(level) && name in BOOLEAN_AND_NIL_MODULES -> "module_reserved"
        name in run.compiling -> "module_in_definition"
        else -> null
    }
}

/** How a unit's [expansion] ends its module, if it does. */
private fun ended(expansion: Expansion, run: Run): Ended? =
    when (expansion) {
        is Expansion.Expanded -> null
        is Expansion.Error ->
            run.crash?.takeIf { it.error === expansion }?.let { Ended.Crashed(expansion, it.exception) }
                ?: Ended.Raised(expansion)
        is Expansion.Unported -> Ended.Stopped(expansion.at)
        is Expansion.Opaque -> Ended.Stopped(expansion.at)
    }

/**
 * `eval_callbacks/5` for `@before_compile` (`Mo:495`, `:513–528`): once the body has run, each entry's `M.F(env)`,
 * oldest first, dispatched as a required call at the module's line in [env], the env the body leaves. A function entry,
 * which Elixir applies, isn't modelled; nor is a value the expander doesn't know, which stops at its first write.
 */
private fun beforeCompile(module: Pending.Module, compiling: Compiling, env: Env, run: Run): Ended? {
    val entries = when (val value = compiling.attributes.final["before_compile"]) {
        null -> emptyList()
        is AttributeValue.Known -> (value.term as? Term.List)?.elements?.asReversed()
        AttributeValue.Unknown -> null
    }
    val write = { compiling.effects.firstOrNull { (it.effect as? Effect.Write)?.name == "before_compile" }?.at }
    val meta = module.node.meta.let {
        Meta(it.origin, it.start, it.end, it.keys.filterIsInstance<Meta.Key.Location>() + REQUIRED, built = true)
    }
    val atom = { name: String -> ElixirAst.Literal.Atom(meta, name) }
    var callbackEnv = env

    for (entry in entries ?: return Ended.Stopped(write() ?: module.node)) {
        val pair = entry as? Term.Pair
        val receiver = (pair?.first as? Term.Atom)?.name
        val name = (pair?.second as? Term.Atom)?.name

        if (receiver == null || name == null) return Ended.Stopped(write() ?: module.node)

        val call = ElixirAst.Call(
            meta,
            ElixirAst.Call(meta, atom("."), listOf(atom(receiver), atom(name))),
            listOf(ElixirAst.Call(meta, atom("__ENV__"), null)),
        )
        val expansion = dispatchRequire(receiver, name, call, ExState.empty(run.level), callbackEnv, run) { _, _ ->
            Expansion.Unported(call)
        }

        ended(expansion, run)?.let { return it }
        callbackEnv = (expansion as Expansion.Expanded).env
    }

    return null
}

private val REQUIRED = Meta.Key.Entry("required", Meta.Value.Atom("true"))

/**
 * The checks once [module]'s body has run, each reported in the module's env, or a local call's in its calling
 * definition's: how the first that raises or stops ends the module, if one does.
 */
private fun postModule(module: Pending.Module, compiling: Compiling, tainted: Boolean, run: Run): Ended? {
    val checks = postModuleChecks(
        run.level,
        module.name,
        module.node,
        compiling.table.entries.mapValues { (_, entry) ->
            Defined(entry.kind, entry.at, entry.clauses > 0, entry.checksClauses)
        },
        compiling.calls,
        compiling.usedPrivate.toList(),
        compiling.imports,
        compiling.attributes::values,
        tainted,
    )

    for (check in checks) {
        when (check) {
            is PostModuleCheck.Error -> {
                val env = module.env.copy(function = check.caller)

                reportOrEnd(check.site, check.at, env, run)?.let { return ended(it, run) }
            }
            PostModuleCheck.Stop -> return Ended.Stopped(module.node)
        }
    }

    return null
}

/** The names `elixir_module` reserves on every release. */
private val RESERVED_MODULES =
    setOf("Elixir.Any", "Elixir.BitString", "Elixir.PID", "Elixir.Reference", "Elixir.Elixir", "Elixir")

/** The names `elixir_module` reserves from [BOOLEAN_AND_NIL_MODULES_RESERVED]. */
private val BOOLEAN_AND_NIL_MODULES = setOf("Elixir.True", "Elixir.False", "Elixir.Nil")
