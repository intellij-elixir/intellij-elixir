package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.expander.ExpansionResult.Ended
import org.elixir_lang.expander.ExpansionResult.Owner
import org.elixir_lang.language_level.ElixirLanguageFeature.BOOLEAN_AND_NIL_MODULES_RESERVED
import org.elixir_lang.language_level.ElixirLanguageFeature.MODULE_NAME_REJECTS_SLASHES
import org.elixir_lang.lowering.ElixirAst
import java.util.Collections
import java.util.IdentityHashMap

/** A module whose body or definitions are being expanded: what it has defined so far, and its local calls. */
internal class Compiling(body: ElixirAst) {
    val table = DefinitionTable()

    /** Each definition's local calls, in expansion order. */
    val calls = LinkedHashMap<NameArity, MutableList<LocalCall<ElixirAst>>>()

    /** The private macros dispatched as local macros, in the order they were first dispatched. */
    val usedPrivate = LinkedHashSet<NameArity>()

    private val statements: MutableSet<ElixirAst> = Collections.newSetFromMap(IdentityHashMap())

    init {
        addStatements(body)
    }

    /** Whether [node] is a statement of the module body, or of a block that is one. */
    fun isStatement(node: ElixirAst): Boolean = node in statements

    private fun addStatements(node: ElixirAst) {
        if (node is ElixirAst.Block) node.expressions.forEach(::addStatements) else statements += node
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
     * @property ordered whether [node] is a statement of the module body
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
    val compiling = Compiling(module.body)

    nameError(name, module.isAtom, run)?.let { kind ->
        return ExpansionResult(
            name, compiling.table, emptyList(), emptyList(), Ended.Raised(Expansion.Error(kind, module.node)),
            emptyList(), emptySet(), emptyList(),
        )
    }

    val enclosing = run.compiling.put(name, compiling)
    val enclosingPending = run.pending
    val consultedFrom = run.consulted.size
    val errors = mutableListOf<Reported>()
    var errorsFrom = run.errors.size
    val units = mutableListOf<ExpansionResult.Unit>()
    val nested = mutableListOf<ExpansionResult>()

    run.pending = mutableListOf()

    val body = Expander.expand(module.body, module.state, module.env, run)
    var ended = ended(body, run)

    units += ExpansionResult.Unit(Owner.ModuleBody, module.body, true, body)

    if (ended?.raises != true) {
        var ordered = true

        for (pending in run.pending) {
            val unitEnded = when (pending) {
                is Pending.Definition -> {
                    ordered = ordered && pending.ordered

                    val (owner, expansion) = storeDefinition(pending, compiling, run)

                    units += ExpansionResult.Unit(owner, pending.node, ordered, expansion)
                    ended(expansion, run)
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
    }

    if (ended == null) {
        ended = checkLocals(module, compiling, tainted = run.errors.size > errorsFrom || errors.isNotEmpty(), run)
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
        units.mapNotNull { it.expansion as? Expansion.Opaque },
        run.consulted.subList(consultedFrom, run.consulted.size).toSet(),
        nested,
    )

    run.load(result)

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
 * The checks of [module]'s local calls once its body has run, each reported in the calling definition's env: how the
 * first that raises ends the module, if one does.
 */
private fun checkLocals(module: Pending.Module, compiling: Compiling, tainted: Boolean, run: Run): Ended? {
    val errors = localErrors(
        run.level,
        compiling.table.kinds,
        compiling.calls,
        compiling.usedPrivate.toList(),
        tainted,
    )

    for ((site, call, caller) in errors) {
        val env = module.env.copy(function = caller)

        reportOrEnd(site, call.at, env, run)?.let { return ended(it, run) }
    }

    return null
}

/** The names `elixir_module` reserves on every release. */
private val RESERVED_MODULES =
    setOf("Elixir.Any", "Elixir.BitString", "Elixir.PID", "Elixir.Reference", "Elixir.Elixir", "Elixir")

/** The names `elixir_module` reserves from [BOOLEAN_AND_NIL_MODULES_RESERVED]. */
private val BOOLEAN_AND_NIL_MODULES = setOf("Elixir.True", "Elixir.False", "Elixir.Nil")
