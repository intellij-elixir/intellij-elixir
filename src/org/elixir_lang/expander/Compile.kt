package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangObject
import com.intellij.openapi.progress.ProgressManager
import org.elixir_lang.NameArity
import org.elixir_lang.expander.ExpansionResult.Ended
import org.elixir_lang.expander.ExpansionResult.Owner
import org.elixir_lang.expander.interpreter.Binding
import org.elixir_lang.expander.interpreter.Evaluated
import org.elixir_lang.expander.interpreter.Given
import org.elixir_lang.expander.interpreter.Interpreter
import org.elixir_lang.expander.interpreter.PartsOutcome
import org.elixir_lang.expander.interpreter.list
import org.elixir_lang.expander.interpreter.part
import org.elixir_lang.expander.interpreter.partsOf
import org.elixir_lang.language_level.ElixirLanguageFeature.BOOLEAN_AND_NIL_MODULES_RESERVED
import org.elixir_lang.language_level.ElixirLanguageFeature.MODULE_NAME_REJECTS_SLASHES
import org.elixir_lang.language_level.ElixirLanguageFeature.POST_MODULE_LOCAL_CHECKS_TYPED
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
internal class Compiling(
    body: ElixirAst,
    level: ElixirLanguageLevel,
    /**
     * Whether Elixir runs the body where the expander does: its `defmodule` is a statement the body around it is
     * certain at.
     */
    known: Boolean = true,
    /** Whether its `defmodule`, and each one around it, is placed where Elixir runs it ([BodyValues.placed]). */
    placed: Boolean = true,
) {
    val table = DefinitionTable()

    /** Each definition's local calls, in expansion order. */
    val calls = LinkedHashMap<NameArity, MutableList<LocalCall<ElixirAst>>>()

    /** Each definition's clauses, in the order they were stored. */
    val clauses = LinkedHashMap<NameArity, MutableList<ModuleClauses.Clause>>()

    /** Each `quote` the definitions' bodies hold. */
    val quotes = ModuleClauses.Quotes()

    /** What each call and bitstring segment of the definitions' bodies expanded as. */
    val dispatches = ModuleClauses.Dispatches()

    /**
     * The calls a hidden definition's body brought back under the name it was made overridable as, which stay when that
     * name is made overridable again where its checks visit the hidden definition's own body, and go otherwise.
     */
    val hiddenCalls: MutableSet<LocalCall<ElixirAst>> =
        Collections.newSetFromMap(IdentityHashMap())

    /** Whether a hidden definition's body is checked as the definition it was stored as. */
    val checksHiddenBodies = POST_MODULE_LOCAL_CHECKS_TYPED.isSufficient(level)

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

    /**
     * Whether the definition being expanded is in a file other than the run's, as `retrieve_location/2` gives it: it
     * took `@file`, or it has a `keep:` location.
     */
    var definitionMoved = false

    /** The struct `defstruct` recorded, if it has run. */
    var struct: ModuleStruct? = null

    /** How the module ends, once something has ended it: the first stop or raise, as the walk goes on after a stop. */
    var ended: Ended? = null

    val definitions = LinkedHashMap<NameArity, AttributeLog.DefinitionAttributes>()

    val unnamed = mutableListOf<AttributeLog.DefinitionAttributes>()

    val statements = Statements().apply { add(body) }

    /** What the module body's statements bound so far, and how far what ran is Elixir's. */
    val values = BodyValues(statements, known, placed)

    /**
     * Whether code the expander doesn't follow may have written the module ([mayHaveWritten]): its tables are then
     * read as unknown, and what it defines isn't known.
     */
    var written = false

    /** Whether a definition's clauses read an attribute that an effect that may not run may have changed. */
    var readChanged = false

    /**
     * Whether the module's tables hold what Elixir's do, for an error computed from them and for what other files read:
     * no definition or effect on the definitions ran where Elixir may not run it, no definition holds code the port
     * can't read or reads an attribute that may have changed ([readChanged]), and no write the expander doesn't follow.
     * An effect that may not run that writes only attributes leaves unknown only what reads them.
     */
    fun tablesCertain(): Boolean = values.ordered && !values.unread && !readChanged && !written

    /** Each call `@` built, to the `@`. */
    private val built = IdentityHashMap<ElixirAst, ElixirAst>()

    /** Whether [node] is a statement of the module body, or of a block that is one. */
    fun isStatement(node: ElixirAst): Boolean = statements.contains(node)

    fun rewrote(call: ElixirAst, rewrite: ElixirAst) = statements.rewrote(call, rewrite)

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
        get() = AttributeLog(
            effects,
            reads,
            attributes.final,
            definitions,
            unnamed,
            attributes.accumulating,
            attributes.heads("callback"),
            attributes.heads("macrocallback"),
        )
}

/**
 * The values a module body's or the file's top-level statements bound so far, by version, which their code runs from
 * and binds into, and how far what ran is Elixir's: whether a raise is the body's for certain.
 *
 * Elixir expands a whole body before it runs any of it, so an error in expanding the body is the body's where Elixir
 * runs it at all ([known]). A raise while it runs is the body's where every statement before the raise ran with
 * Elixir's outcome ([certainAt]).
 */
internal class BodyValues(
    val statements: Statements,
    /**
     * Whether Elixir runs the body where the expander does: the file's, and a module body's where its `defmodule` is a
     * statement the body around it is certain at, until the body's own expansion stops ([expanded]).
     */
    known: Boolean = true,
    /**
     * Whether what Elixir defines in the body is what the walk finds: its `defmodule`, and each one around it, is a
     * statement, and neither the body nor any body around it, up to the `defmodule` in it, holds a definition the port
     * can't read ([unread]) or stopped expanding ([expanded]).
     */
    placed: Boolean = true,
) {
    var known = known
        private set

    var placed = placed
        private set

    /** Whether a definition of the body holds code the port can't read. */
    var unread = false
        private set

    val bindings = HashMap<Binding, OtpErlangObject>()

    /**
     * Whether Elixir defines what the body has defined so far in the order the expander did: every definition, effect
     * on the table and check so far ran at a statement.
     */
    var ordered = true
        private set

    /** Each match and statement the walk ran as a unit, to what it gave where it didn't raise. */
    private val units = IdentityHashMap<ElixirAst, Evaluated>()

    /**
     * The site of each unit the walk ran, which a block holding it doesn't run again, to whether its outcome is
     * Elixir's: `false` where Elixir may have raised there.
     */
    private val outcomes = IdentityHashMap<ElixirAst, Boolean>()

    /**
     * Whether the evaluator declined code of the unit being run that no port built ([Run.isPorted]): Elixir may have
     * raised there.
     */
    private var declined = false

    /**
     * The body's code expanded to [expansion] before any of it ran. Where the expansion stopped, or a macro's output in
     * it [assumed] a raise not to happen, Elixir may have raised expanding it, so whether it runs the body isn't known;
     * where it stopped, nor is what it defines.
     */
    fun expanded(expansion: Expansion, assumed: Boolean) {
        if (!expansion.runs || assumed) known = false
        if (!expansion.runs) placed = false
    }

    /**
     * The unit being run, [pending], took a raise not to happen, in code it ran or a macro's output it expanded: Elixir
     * may have raised there. A nested module's assumptions are its own.
     */
    fun assumed(pending: Pending) {
        if (pending !is Pending.Module) declined = true
    }

    /**
     * A definition of the body holds code the port can't read, escaped as it is with any `unquote` in it, whose
     * expression Elixir expands with the body before any of it runs. Whether Elixir runs the body isn't known.
     */
    fun unread() {
        known = false
        placed = false
        unread = true
    }

    /**
     * The walk ran the unit at [site], which gave [raw], classified once: its raise is the body's only where the body
     * is certain at it. Elixir ran the unit where the expander did when it is [ordered], and its outcome is Elixir's
     * when it is [known] as well, it didn't stop, and the evaluator declined none of its code and assumed no raise away.
     */
    fun outcome(site: ElixirAst, raw: Expansion?, ordered: Boolean, known: Boolean): Expansion? {
        val expansion = raw?.certain(certainAt(site), site)

        this.ordered = this.ordered && ordered
        outcomes[site] = ordered && known && expansion.runs && !declined
        declined = false

        return expansion
    }

    /**
     * Whether a raise at [site], a unit's node, is the body's for certain: Elixir runs the body and has defined what it
     * defined so far in the expander's order, every statement before [site] ran with Elixir's outcome, and the unit
     * declined nothing and assumed no raise away so far. `null` is the body's end, for a raise once every unit has run.
     */
    fun certainAt(site: ElixirAst?): Boolean =
        known && ordered && !declined && statements.allBefore(site) { outcomes[it] }

    /** [node], code of the body, run where the body runs it. */
    fun evaluate(node: ElixirAst, state: ExState, env: Env, run: Run): Evaluated =
        recorded(Interpreter.body(node, bindings, state, env, run), node, run)

    /**
     * The match [node], a statement of the body, run where the body runs it. The statements of its right side ran before
     * it, so their values are taken, not run again.
     */
    fun match(node: ElixirAst.Call, state: ExState, env: Env, run: Run): Evaluated {
        val (pattern, right) = node.arguments!!
        val evaluated = when (val value = ran(right, state, env, run)) {
            is Evaluated.Value -> recorded(Interpreter.bind(pattern, value.value, node, bindings, state, env, run), pattern, run)
            is Evaluated.Declined -> value.also { Interpreter.bindDeclined(pattern, it.holds, bindings, env, run) }
            is Evaluated.Raised -> value
        }

        if (evaluated !is Evaluated.Raised) units[node] = evaluated

        return evaluated
    }

    /** [node], a statement of the body whose value nothing takes, run where the body runs it. */
    fun statement(node: ElixirAst, state: ExState, env: Env, run: Run): Evaluated =
        evaluate(node, state, env, run).also { if (it !is Evaluated.Raised) units[node] = it }

    /**
     * [node]'s value, its statements run: a block's expressions are taken in turn ([partsOf]), and its value is its
     * last's; a proper list's is its elements'; a macro call's is its output's; and a match or statement the walk ran
     * gives what it gave. Code the walk ran as a unit raised nowhere the unit didn't, so declining it declines only its
     * value.
     */
    private fun ran(node: ElixirAst, state: ExState, env: Env, run: Run): Evaluated {
        ProgressManager.checkCanceled()

        return when (node) {
            is ElixirAst.Block if node.expressions.isNotEmpty() -> {
                val last = node.expressions.last()
                // A definition or effect the walk ran gave no value a block before its last takes.
                val taken = node.expressions.filter { it === last || it !in outcomes || it in units }

                built(partsOf(taken) { ran(it, state, env, run).part() }) { it.last() }
            }
            is ElixirAst.ListNode if node.elements.none { isCall(it, "|", 2) } ->
                built(partsOf(node.elements) { ran(it, state, env, run).part() }) { list(it) }
            else -> when {
                node in outcomes -> when (val value = units[node] ?: Interpreter.body(node, bindings, state, env, run)) {
                    is Evaluated.Value -> value
                    // The unit counted what its calls were given as it ran.
                    is Evaluated.Declined -> Evaluated.Declined(Given(), value.holds)
                    is Evaluated.Raised -> Evaluated.Declined(Given())
                }
                else ->
                    (run.dispatchesAt(env)?.of(node) as? ModuleClauses.Dispatched.Macro)
                        ?.takeIf { run.statementsAt(env)?.isRewritten(node) == true }
                        ?.let { ran(it.output, state, env, run) }
                        ?: evaluate(node, state, env, run)
            }
        }
    }

    /** What a value's parts gave ([outcome]), as running code gives: [value] of their values, or their decline or raise. */
    private fun built(outcome: PartsOutcome<Evaluated.Raised>, value: (List<OtpErlangObject>) -> OtpErlangObject): Evaluated =
        when (outcome) {
            is PartsOutcome.Values -> Evaluated.Value(value(outcome.values))
            is PartsOutcome.Declines -> Evaluated.Declined(outcome.declined.given, outcome.declined.holds)
            is PartsOutcome.Raises -> outcome.raise
        }

    /** `elixir_quote:[function]/n` of [args], which the escape of a definition at [node] calls. */
    fun quoteHelper(function: String, args: List<OtpErlangObject>, node: ElixirAst, state: ExState, env: Env, run: Run) =
        recorded(Interpreter.quoteHelper(function, args, node, state, env, run), node, run)

    private fun recorded(evaluated: Evaluated, node: ElixirAst, run: Run): Evaluated =
        evaluated.also {
            if (it is Evaluated.Declined && !run.isPorted(node)) {
                declined = true
                mayHaveWritten(it.given.of(run.compiling))
            }
        }
}

/**
 * The statements of a module body or of the file's top level, in the order the body runs them: what runs once, where
 * the body does. A match runs the statements of its right side before it binds, and a macro's output runs in place of
 * its call.
 */
internal class Statements {
    /** Each statement to the one it runs within: a match for its right side's, a rewritten call for its output's. */
    private val parents = IdentityHashMap<ElixirAst, ElixirAst?>()

    /** The statements each match or rewritten call runs, in order. */
    private val children = IdentityHashMap<ElixirAst, MutableList<ElixirAst>>()

    /** The statements at the top, in order. */
    private val top = mutableListOf<ElixirAst>()

    /** The statements whose value nothing takes, which only the walk's units run. */
    private val whole: MutableSet<ElixirAst> = Collections.newSetFromMap(IdentityHashMap())

    /** The calls a macro's output was expanded in place of. */
    private val rewritten: MutableSet<ElixirAst> = Collections.newSetFromMap(IdentityHashMap())

    /** The statements that raise nowhere: their value is known, or a port that ports its raises itself built them. */
    private val inert: MutableSet<ElixirAst> = Collections.newSetFromMap(IdentityHashMap())

    fun contains(node: ElixirAst): Boolean = parents.containsKey(node)

    /** [node], a statement, raises nowhere, so no unit need run it. */
    fun inert(node: ElixirAst) {
        inert += node
    }

    /** Whether [node] is a statement whose value nothing takes, as a match's right side's is taken. */
    fun isWhole(node: ElixirAst): Boolean = node in whole

    /** Whether a macro's output was expanded in place of [node], whose statements are its own. */
    fun isRewritten(node: ElixirAst): Boolean = node in rewritten

    /** [rewrite], a macro's output in place of [call], whose statements are statements when [call] is one. */
    fun rewrote(call: ElixirAst, rewrite: ElixirAst) {
        if (contains(call)) {
            rewritten += call
            add(rewrite, isWhole(call), call)
        }
    }

    /**
     * A block's expressions are statements, as `expand_block/5` runs them: a `_ = for` before the last is its `for`, and
     * where the block's value is taken, as a match takes its right side's, only its last expression's is. So are the
     * elements of a statement that is a list, as `defimpl`'s output for a list of types is, and the head and tail of
     * its `[h | t]`, and what a statement that is a match binds, within it: each runs where the statement does.
     */
    fun add(node: ElixirAst, isWhole: Boolean = true, within: ElixirAst? = null) {
        ProgressManager.checkCanceled()

        when (node) {
            is ElixirAst.Block -> {
                val last = node.expressions.lastOrNull()

                for (expression in node.expressions) {
                    add((if (expression !== last) discardedFor(expression) else null) ?: expression, isWhole || expression !== last, within)
                }
            }
            is ElixirAst.ListNode -> for (element in node.elements) {
                if (isCall(element, "|", 2)) {
                    (element as ElixirAst.Call).arguments!!.forEach { add(it, isWhole, within) }
                } else {
                    add(element, isWhole, within)
                }
            }
            else -> {
                parents[node] = within
                (within?.let { children.getOrPut(it) { mutableListOf() } } ?: top) += node
                if (isWhole) whole += node

                if (isCall(node, "=", 2)) add((node as ElixirAst.Call).arguments!![1], isWhole = false, within = node)
            }
        }
    }

    /**
     * Whether every statement the body runs before [site], a unit's node, or the statement that runs it, has an
     * [outcome] or is [inert], where a statement a unit ran has an outcome and a match's or a rewritten call's covers
     * the statements it runs that no unit did. `null` is the body's end. `false` where [site] isn't a statement.
     */
    fun allBefore(site: ElixirAst?, outcome: (ElixirAst) -> Boolean?): Boolean =
        (site == null || contains(site)) && allBefore(top, site, false, outcome) != false

    /**
     * `true` where every statement of [nodes] before [site], and within those, has an [outcome] or is [covered] by the
     * statement running it, and none has `false`; `false` where one doesn't; `null` once [site] is reached.
     */
    private fun allBefore(
        nodes: List<ElixirAst>,
        site: ElixirAst?,
        covered: Boolean,
        outcome: (ElixirAst) -> Boolean?,
    ): Boolean? {
        for (node in nodes) {
            ProgressManager.checkCanceled()

            val own = outcome(node)

            if (own == false) return false

            // The units a statement runs before it, a nested module's or a match's in its right side, ran before a raise in it.
            if (node === site) return if (allBefore(children[node].orEmpty(), null, true, outcome) == false) false else null

            when (allBefore(children[node].orEmpty(), site, covered || own == true, outcome)) {
                null -> return null
                false -> return false
                true -> Unit
            }

            if (own == null && !covered && node !in rewritten && node !in inert) return false
        }

        return true
    }
}

/** What a body defines, which Elixir evaluates once the body is expanded. */
internal sealed interface Pending {
    /**
     * A `def*` call, whose unquote fragments the walk evaluates when it stores the definition.
     *
     * @property node the `def*` call
     * @property head the call and guard being defined
     * @property body the options, or `null` for a bodiless head
     * @property fragments what the escape of [head] and [body] evaluates, or `null` where neither is escaped
     * @property env the env at [node]
     * @property statement whether [node] is a statement of the module body
     * @property checksClauses whether Elixir checks the definition for clauses: it has no unquotes, and its head
     *   wasn't quoted
     * @property delegatesTo what the head calls, when a `defdelegate` defined it
     */
    class Definition(
        val kind: DefinitionTable.Kind,
        val node: ElixirAst.Call,
        val head: ElixirAst,
        val body: ElixirAst?,
        val fragments: Fragments?,
        val env: Env,
        val statement: Boolean,
        val checksClauses: Boolean,
        val delegatesTo: DefinitionTable.Delegation? = null,
        val injectedBy: ElixirAst.Call?,
    ) : Pending

    /**
     * What a macro does to its module's table when the body runs, at [node]'s place among the definitions.
     *
     * @property ordered whether [node] is a statement of the module body
     * @property queued what runs after the effect, which depends on what it did
     * @property apply does it, given [ordered], and gives `null`, or the error the body raises there, or the
     *   [Expansion.Unported] where the effect can't be followed
     */
    class Effect(
        val node: ElixirAst,
        val ordered: Boolean,
        val queued: () -> List<Pending> = { emptyList() },
        val apply: (Compiling, Boolean) -> Expansion?,
    ) : Pending

    /**
     * A module.
     *
     * @property node the `defmodule` call
     * @property name the module's name as atom text, or, where it isn't an atom, as `inspect/1` shows it
     * @property isAtom whether the name is an atom
     * @property env the env its body starts from
     * @property state the state its body starts from
     * @property settle what the body queued, once it is expanded, in place of what the macro that made the module can't
     *   know before the body has run
     * @property applied the `@before_compile` callbacks, by module and function, that the macro that made the module
     *   applies itself, because it knows what they do
     * @property injectedBy the call whose evaluated output wrote the `defmodule`, which injects what its body defines
     * @property outer each version [state] reads a variable at, to the version the body the module is in reads it at
     * @property placed whether the body the module is in was [BodyValues.placed] where its expansion reached [node]:
     *   what that expansion did after it can't change what the module defines
     */
    class Module(
        val node: ElixirAst.Call,
        val name: String,
        val isAtom: Boolean,
        val body: ElixirAst,
        val env: Env,
        val state: ExState,
        val settle: ((List<Pending>) -> List<Pending>)? = null,
        val applied: Set<Pair<String, String>> = emptySet(),
        val injectedBy: ElixirAst.Call? = null,
        val outer: Map<Int, Int> = emptyMap(),
        val placed: Boolean = true,
    ) : Pending

    /**
     * What a call in the module body does to the module's attributes.
     *
     * @property at the `@` that built the call, or the call
     * @property statement whether [at] is a statement of the module body
     * @property resolve the effect from the attributes as they are where the walk reaches it, where [effect] depends on
     *   them; [effect] is what the call leaves when they aren't known
     * @property raises the error the call's arguments raise where the walk reaches it, if they do
     */
    class Attribute(
        val effect: org.elixir_lang.expander.Effect,
        val at: ElixirAst,
        val statement: Boolean,
        val raises: (() -> Expansion?)? = null,
        val resolve: ((AttributeTable) -> org.elixir_lang.expander.Effect)? = null,
    ) : Pending

    /**
     * A check that reads no table, run where the walk reaches [node], in a module body or the file's top level.
     *
     * @property ordered whether [node] is a statement of the body or the file
     * @property check gives `null`, or how the body ends there: the error it raises, or where it stops
     */
    class Check(val node: ElixirAst, val ordered: Boolean, val check: () -> Expansion?) : Pending

    /** `=`, a statement of a module body or of the file's top level, which the walk runs, binding what it matches. */
    class Match(val node: ElixirAst, val state: ExState, val env: Env) : Pending

    /**
     * A statement of a module body or of the file's top level that no other unit runs and whose value isn't known, which
     * a port didn't build: the evaluator runs it where the body does. Where it declines, Elixir may have raised there,
     * so no later raise is the body's for certain.
     */
    class Statement(val node: ElixirAst, val state: ExState, val env: Env) : Pending
}

/** Whether the entry's node is a statement of the body, a module's or the file's, whose [statements] these are. */
internal fun Pending.isStatement(statements: Statements): Boolean =
    when (this) {
        is Pending.Definition -> statement
        is Pending.Effect -> ordered
        is Pending.Check -> ordered
        is Pending.Attribute -> statement
        is Pending.Module -> statements.contains(node)
        is Pending.Match, is Pending.Statement -> true
    }

/** The node the entry runs where the body is. */
internal val Pending.site: ElixirAst
    get() = when (this) {
        is Pending.Definition -> node
        is Pending.Effect -> node
        is Pending.Module -> node
        is Pending.Attribute -> at
        is Pending.Check -> node
        is Pending.Match -> node
        is Pending.Statement -> node
    }

/** The entry as it is when its node is a statement of the module body. */
internal fun Pending.asStatement(): Pending =
    when (this) {
        is Pending.Definition ->
            Pending.Definition(kind, node, head, body, fragments, env, true, checksClauses, delegatesTo, injectedBy)
        is Pending.Effect -> Pending.Effect(node, true, queued, apply)
        is Pending.Attribute -> Pending.Attribute(effect, at, true, raises, resolve)
        is Pending.Check -> Pending.Check(node, true, check)
        is Pending.Module, is Pending.Match, is Pending.Statement -> this
    }

/** [replacement] in place of what [Run.pending] gained since it had [from] entries, which a macro's own output queued. */
internal fun Run.replacePending(from: Int, replacement: List<Pending>) {
    pending.subList(from, pending.size).clear()
    pending += replacement
}

/**
 * What [compilePending] gives: how the file's top level ends, if it does, where it first stopped or raised; each module
 * compiled; and whether the run [endsRun] at an error from certain inputs, after which Elixir compiles nothing.
 */
internal class Walked(val ended: Expansion?, val modules: List<ExpansionResult>, val endsRun: Boolean)

/**
 * Each module [run]'s expansion so far defined, compiled in turn, and each `=` and check of the file's top level run in
 * its place, until one raises or reaches a raise from certain inputs, after which none is defined.
 */
internal fun compilePending(run: Run): Walked {
    val modules = mutableListOf<ExpansionResult>()
    val values = run.fileValues
    var stopped: Expansion? = null

    for (pending in run.pending) {
        ProgressManager.checkCanceled()

        val statement = pending.isStatement(values.statements)
        var ordered = true
        var known = true
        val (raw, assumed) = run.assuming { when (pending) {
            is Pending.Module -> {
                val result = compileModule(pending, run, values)

                modules += result

                // The file's run ends where it first stopped, or at the module's own stop where nothing stopped before.
                if (result.ended.endsRunAt(statement)) {
                    return Walked(stopped ?: (result.ended as? Ended.Stopped)?.let { Expansion.Unported(it.at) }, modules, true)
                }

                known = result.certain
                null
            }
            is Pending.Match -> matched(pending, values, run)
            is Pending.Check -> {
                ordered = pending.ordered

                pending.check()
            }
            is Pending.Statement -> ran(pending, values, run)
            is Pending.Definition, is Pending.Effect, is Pending.Attribute -> null
        } }

        if (assumed) values.assumed(pending)

        val ended = values.outcome(pending.site, raw, ordered, known)

        // Nothing after a raise at a statement runs, whether or not Elixir raised where the file stopped before it.
        if (raw is Expansion.Error && statement) return Walked(stopped ?: ended, modules, true)
        if (ended is Expansion.Unported || ended is Expansion.Opaque) stopped = stopped ?: ended
    }

    return Walked(stopped, modules, false)
}

/** Whether a unit that ended as [this] ran as Elixir does: a stop hides what Elixir did there. */
private val Expansion?.runs: Boolean
    get() = this == null || this is Expansion.Expanded || this is Expansion.Error

/**
 * `elixir_module:compile`: [module]'s name checked, its body expanded, then each definition and nested module it
 * defines in the order Elixir evaluates the body, then the checks of its local calls. A module a call's output wrote
 * compiles after that output, so the call is restored as the one injecting what it defines. Its body reads the
 * variables it is given from [enclosing], the values the body it is in has bound, and whether Elixir runs it where the
 * expander does: at a statement [enclosing] is certain at.
 */
internal fun compileModule(module: Pending.Module, run: Run, enclosing: BodyValues): ExpansionResult =
    module.injectedBy?.let { call -> run.expandInjected(call) { compile(module, run, enclosing) } }
        ?: compile(module, run, enclosing)

/**
 * [match] run from [values], which it binds into: `null`, or where it ends the body it is in, an error only where
 * [values] is certain at it. A value the evaluator can't produce leaves what the match binds unbound.
 */
private fun matched(match: Pending.Match, values: BodyValues, run: Run): Expansion? =
    when (val evaluated = values.match(match.node as ElixirAst.Call, match.state, match.env, run)) {
        is Evaluated.Value, is Evaluated.Declined -> null
        is Evaluated.Raised -> Expansion.Error(evaluated.exception, match.node)
    }

private fun ran(statement: Pending.Statement, values: BodyValues, run: Run): Expansion? =
    when (val evaluated = values.statement(statement.node, statement.state, statement.env, run)) {
        is Evaluated.Value, is Evaluated.Declined -> null
        is Evaluated.Raised -> Expansion.Error(evaluated.exception, statement.node)
    }

private fun compile(module: Pending.Module, run: Run, enclosing: BodyValues): ExpansionResult {
    val name = module.name
    val statement = module.isStatement(enclosing.statements)
    val compiling =
        Compiling(module.body, run.level, statement && enclosing.certainAt(module.node), statement && module.placed)
    val values = compiling.values

    for ((version, outer) in module.outer) {
        enclosing.bindings[Binding(outer)]?.let { values.bindings[Binding(version)] = it }
    }

    /** Whether a raise once the body has run is the module's for certain. */
    fun sure() = values.certainAt(null)

    nameError(name, module.isAtom, run)?.let { kind ->
        return ExpansionResult(
            name, compiling.table, emptyList(), emptyList(), emptyList(),
            ended(Expansion.Error(kind, module.node), values.known, run)!!,
            emptyList(), emptySet(), emptyList(), AttributeLog.EMPTY, null, certain = false,
            definitionsKnown = false,
        )
    }

    val enclosing = run.compiling.put(name, compiling)
    val enclosingPending = run.pending
    val consultedFrom = run.consulted.size
    val errors = mutableListOf<Reported>()
    var errorsFrom = run.errors.size
    val warnings = mutableListOf<Warning>()
    var warningsFrom = run.warnings.size
    val units = mutableListOf<ExpansionResult.Unit>()
    val nested = mutableListOf<ExpansionResult>()
    val effectOpaque = mutableListOf<Expansion.Opaque>()

    run.pending = mutableListOf()
    run.counters.start(name)

    // Elixir expands the whole body before it runs any of it, so an error expanding it is the module's wherever Elixir
    // runs the `defmodule`.
    val (expanded, assumed) = run.assuming {
        unlessTooDeep({ Expansion.Unported(module.body) }) { Expander.expand(module.body, module.state, module.env, run) }
    }
    values.expanded(expanded, assumed)

    val body = expanded.certain(values.known)

    compiling.ended = ended(expanded, values.known, run).skipping()
    units += ExpansionResult.Unit(Owner.ModuleBody, module.body, true, body)

    // A body or callback that raised, or reached a raise that may be Elixir's, keeps the definitions it queued before
    // that, and the attributes they read, as one that stopped does. Elixir evaluates none of it, so no clause is
    // expanded and nothing else it queued runs.
    fun runEnded() = compiling.ended?.endsRun == true

    /** [unit], or a stop at [at] where a value it converts nests too deeply for the thread's stack. */
    fun stoppedIfTooDeep(at: ElixirAst, unit: () -> Expansion?): Expansion? = unlessTooDeep({ Expansion.Unported(at) }, unit)

    /** Each of [queued] in the order the body runs them, until one raises. */
    fun walk(queued: List<Pending>) {
        val queue = ArrayDeque(queued)

        while (queue.isNotEmpty()) {
            ProgressManager.checkCanceled()

            val pending = queue.removeFirst()

            // Whether Elixir ran the unit where the expander did, and whether what it did there is known: code the unit
            // runs that the expander doesn't follow, a callback's or a nested module's, hides whether Elixir raised.
            var ordered = true
            var known = true
            val statement = pending.isStatement(compiling.statements)
            // Whether a nested module's ending ends this module's run.
            var innerEndsRun = false

            // The unit's record, given its expansion once classified.
            var unit: ((Expansion) -> ExpansionResult.Unit)? = null

            val (raw, assumed) = run.assuming { when (pending) {
                is Pending.Module if runEnded() -> null
                is Pending.Effect if runEnded() -> null
                is Pending.Check if runEnded() -> null
                is Pending.Definition -> stoppedIfTooDeep(pending.node) {
                    val unquoted = unquoted(pending, compiling, run, evaluates = !runEnded())

                    ordered = unquoted.ordered
                    known = !compiling.attributes.callsBack("on_definition")

                    val (owner, stored) = storeDefinition(pending, unquoted, compiling, run, expand = !runEnded())

                    compiling.storedReads(owner)

                    if (stored !is Expansion.Error) {
                        takeDefinitionAttributes(pending, unquoted.head, owner as Owner.Definition, compiling)
                    }

                    // `run_on_definition_callbacks`: a user entry's code isn't followed, and it is given the env, so it
                    // may have written any module being compiled.
                    if (!known && !runEnded()) mayHaveWritten(run.compiling.values)

                    if (runEnded()) {
                        null
                    } else {
                        val isOrdered = values.ordered && ordered

                        unit = { ExpansionResult.Unit(owner, pending.node, isOrdered, it) }
                        stored
                    }
                }
                is Pending.Attribute -> stoppedIfTooDeep(pending.at) {
                    val raises = pending.raises?.takeUnless { runEnded() }?.invoke()

                    if (raises != null) {
                        raises
                    } else {
                        val effect = pending.resolve?.invoke(compiling.attributes) ?: pending.effect

                        compiling.effects += AttributeLog.Logged(effect, pending.at, pending.statement)

                        when (val outcome = applied(effect, pending.statement, compiling, run)) {
                            is EffectOutcome.Raises -> Expansion.Error(outcome.kind, pending.at)
                            // Whether Elixir raises there isn't known, so nothing after it can be compared.
                            EffectOutcome.Unchecked -> Expansion.Unported(pending.at)
                            EffectOutcome.Stored -> null
                        }
                    }
                }
                is Pending.Match -> if (runEnded()) null else matched(pending, values, run)
                is Pending.Check -> {
                    ordered = pending.ordered

                    pending.check()
                }
                is Pending.Statement -> if (runEnded()) null else ran(pending, values, run)
                is Pending.Effect -> stoppedIfTooDeep(pending.node) {
                    ordered = pending.ordered

                    val applied = pending.apply(compiling, pending.ordered)

                    (applied as? Expansion.Opaque)?.let(effectOpaque::add)
                    queue.addAll(0, pending.queued())
                    applied
                }
                is Pending.Module -> {
                    errors += run.errors.subList(errorsFrom, run.errors.size)
                    warnings += run.warnings.subList(warningsFrom, run.warnings.size)

                    val result = compileModule(pending, run, values)

                    errorsFrom = run.errors.size
                    warningsFrom = run.warnings.size
                    nested += result
                    known = result.certain
                    innerEndsRun = result.ended.endsRunAt(statement)
                    when (val inner = result.ended) {
                        Ended.Compiled -> null
                        // `elixir_module:compile/6` raises once the nested module's checks have run.
                        Ended.Tainted -> Expansion.Error("compile_error", pending.node)
                        // A raise in a nested module ends the module around it too.
                        is Ended.Raised -> inner.error
                        is Ended.Crashed -> inner.error
                        is Ended.Stopped -> Expansion.Unported(inner.at)
                    }
                }
            } }

            if (assumed) values.assumed(pending)

            // An error computed from the tables, where code Elixir may have run otherwise changed them, is assumed not to
            // happen: the walk goes on.
            val inputsCertain =
                pending !is Pending.Definition && pending !is Pending.Effect && pending !is Pending.Check ||
                    compiling.tablesCertain()
            val expansion = values.outcome(pending.site, raw, ordered, known)
            val unitEnded = expansion?.let { ended(it, run) }

            unit?.let { units += it(expansion!!) }
            compiling.ended = compiling.ended ?: unitEnded

            // A raise at a statement from certain inputs ends the module body's run, even after an earlier unit stopped:
            // Elixir raised there or before it, so a module that stopped may raise.
            if (statement && raw is Expansion.Error && inputsCertain || innerEndsRun) {
                compiling.ended = (compiling.ended as? Ended.Stopped)?.copy(mayRaise = true) ?: compiling.ended
                return
            }
        }
    }

    walk(module.settle?.invoke(run.pending) ?: run.pending)

    storeNotOverridden(compiling, checked = compiling.ended == null)?.let { compiling.ended = ended(it, sure(), run, compiling.tablesCertain()) }

    compiling.ended = when (val ended = compiling.ended) {
        null -> beforeCompile(module, compiling, (body as Expansion.Expanded).env, run, ::walk)
        // Elixir runs the callbacks of a module that compiles past the stop.
        else -> if (callbacks(compiling)?.isEmpty() == true) ended else ended.skipping()
    }

    // `eval_form/7` stores them again after the callbacks, which may have defined the other kind.
    storeNotOverridden(compiling, checked = compiling.ended == null)?.let { compiling.ended = ended(it, sure(), run, compiling.tablesCertain()) }

    if (compiling.ended == null) {
        compiling.ended =
            postModule(module, compiling, tainted = run.errors.size > errorsFrom || errors.isNotEmpty(), run) { ended(it, sure(), run, compiling.tablesCertain()) }
    }

    errors += run.errors.subList(errorsFrom, run.errors.size)
    warnings += run.warnings.subList(warningsFrom, run.warnings.size)

    run.pending = enclosingPending

    if (enclosing == null) run.compiling.remove(name) else run.compiling[name] = enclosing

    // The errors Elixir reported raise once the module's checks have run, unless its code raised before that, or may
    // have at a stop.
    val ended = compiling.ended ?: when {
        errors.isEmpty() -> Ended.Compiled
        sure() -> Ended.Tainted
        else -> Ended.Stopped(errors.first().at, mayRaise = compiling.tablesCertain())
    }

    // `compile/6`'s `@after_compile` entries are code the expander doesn't follow, given the env: they may have written
    // any module being compiled around this one.
    if ((ended == Ended.Compiled || ended is Ended.Stopped) && compiling.attributes.callsBack("after_compile")) {
        mayHaveWritten(run.compiling.values)
    }

    val result = ExpansionResult(
        name,
        compiling.table,
        units,
        errors,
        warnings,
        ended,
        units.mapNotNull { it.expansion as? Expansion.Opaque } + effectOpaque,
        run.consulted.subList(consultedFrom, run.consulted.size).toSet(),
        nested,
        compiling.log,
        compiling.struct,
        // `compile/6` runs each `@after_compile` entry before it returns, and it may raise. The `@after_verify` entries
        // run once the whole file has compiled.
        certain = ended == Ended.Compiled && sure() && !compiling.attributes.callsBack("after_compile"),
        definitionsKnown = ended == Ended.Compiled && values.placed && compiling.tablesCertain() &&
            !compiling.attributes.callsBack("after_compile"),
    )

    run.load(result) { ModuleClauses.of(result.module, compiling, run.virtualFile) }

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

/**
 * [effect], of a call in [compiling]'s body that is a [statement] or not, applied to [compiling]'s attributes and to
 * those of each other module being compiled that it changes, where it is a statement only if Elixir runs [compiling]'s
 * body wherever the expander does: the first outcome that isn't [EffectOutcome.Stored], if any is. A named module not
 * being compiled here may be compiling in another file's process, or not at all, where Elixir raises.
 */
private fun applied(effect: Effect, statement: Boolean, compiling: Compiling, run: Run): EffectOutcome {
    val own = compiling.attributes.apply(effect, statement)
    val other = effect as? Effect.OtherModule ?: return own
    val others = other.module?.let { listOf(run.compiling[it]) } ?: run.compiling.values.filter { it !== compiling }
    val outcomes = listOf(own) +
        others.map { it?.attributes?.apply(other.effect, statement && compiling.values.known) ?: EffectOutcome.Unchecked }

    // Which module a write names isn't known, so neither is whether Elixir refuses it.
    if (other.module == null) return EffectOutcome.Unchecked

    return outcomes.firstOrNull { it != EffectOutcome.Stored } ?: EffectOutcome.Stored
}

/**
 * The expansion, but an error is the body's only where it [isCertain] there; otherwise it stops at [node], the error's
 * own by default.
 */
internal fun Expansion.certain(isCertain: Boolean, node: ElixirAst? = null): Expansion =
    if (!isCertain && this is Expansion.Error) Expansion.Unported(node ?: at) else this

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
 * How [expansion], of the body or of a check on its statement path, ends its module, if it does: an error is the
 * module's only where it [isCertain]; otherwise the module stops at it, and may raise there where it is computed from
 * certain inputs ([inputsCertain]).
 */
private fun ended(expansion: Expansion, isCertain: Boolean, run: Run, inputsCertain: Boolean = true): Ended? =
    if (!isCertain && expansion is Expansion.Error) {
        Ended.Stopped(expansion.at, mayRaise = inputsCertain)
    } else {
        ended(expansion, run)
    }

/** This ending, where it is a stop, as one that [skipped][Ended.Stopped.skipped] what Elixir runs after it. */
private fun Ended?.skipping(): Ended? = if (this is Ended.Stopped) copy(skipped = true) else this

/** The `@before_compile` entries [compiling] holds, oldest first, or `null` where they aren't known. */
private fun callbacks(compiling: Compiling): List<Term>? =
    when (val value = compiling.attributes.final["before_compile"]) {
        null -> emptyList()
        is AttributeValue.Known -> (value.term as? Term.List)?.elements?.asReversed()
        AttributeValue.Unknown -> null
    }

/**
 * `eval_callbacks/5` for `@before_compile` (`Mo:495`, `:513–542`): once the body has run, each entry's `M.F(env)`,
 * oldest first, dispatched as a required call at the module's line, given [env], the env the body leaves, in the env
 * the callbacks before it left. Its output is a statement of the module body, evaluated with no bindings, and what it
 * queues is [walk]ed before the next runs, as the body's is, even where it stops or raises. An error is the module's
 * where the body is certain at the call, and where the output's expansion stops, whether Elixir runs the body isn't
 * known, as it isn't where the body's own does. A function entry, which Elixir applies, isn't modelled unless the
 * module's macro [applied][Pending.Module.applied] it; nor is a value the expander doesn't know, which stops at its
 * first write.
 */
private fun beforeCompile(
    module: Pending.Module,
    compiling: Compiling,
    env: Env,
    run: Run,
    walk: (List<Pending>) -> Unit,
): Ended? {
    val entries = callbacks(compiling)
    val write = { compiling.effects.firstOrNull { (it.effect as? Effect.Write)?.name == "before_compile" }?.at }
    val meta = module.node.meta.let {
        Meta(it.origin, it.start, it.end, it.keys.filterIsInstance<Meta.Key.Location>() + REQUIRED, built = true)
    }
    val atom = { name: String -> ElixirAst.Literal.Atom(meta, name) }
    var callbackEnv = env

    for ((index, entry) in (entries ?: return Ended.Stopped(write() ?: module.node).skipping()).withIndex()) {
        val pair = entry as? Term.Pair
        val receiver = (pair?.first as? Term.Atom)?.name
        val name = (pair?.second as? Term.Atom)?.name

        if (receiver == null || name == null) return Ended.Stopped(write() ?: module.node).skipping()

        if (receiver to name in module.applied) continue

        val given = ElixirAst.Call(meta, atom("__ENV__"), null)
        val call = ElixirAst.Call(meta, ElixirAst.Call(meta, atom("."), listOf(atom(receiver), atom(name))), listOf(given))

        run.givenEnvs[given] = env
        compiling.statements.add(call)
        // `elixir_module:expand_callback/6` evaluates the output with no binding.
        compiling.values.bindings.clear()
        run.pending = mutableListOf()

        val (expansion, assumed) = run.assuming {
            dispatchRequire(receiver, name, call, ExState.empty(run.level), callbackEnv, run) { _, _ -> Expansion.Unported(call) }
        }

        compiling.values.expanded(expansion, assumed)
        compiling.ended = ended(expansion, compiling.values.certainAt(call), run).skipping()
        walk(run.pending)
        // A stop in what the output queued leaves the callbacks after it unrun.
        compiling.ended?.let { return if (index < entries.lastIndex) it.skipping() else it }
        callbackEnv = (expansion as Expansion.Expanded).env
    }

    return null
}

/**
 * [module], being compiled, where its table and attributes are Elixir's at this point in its body, or `null` where they
 * may differ. They may for a module not being compiled, for which Elixir raises; after code the expander doesn't follow
 * may have written it ([Compiling.written]); and after a stop of any module being compiled, which may hide a change to
 * them, as a nested module may change the one it is in.
 */
internal fun Run.compilingKnown(module: String): Compiling? =
    compiling[module]?.takeUnless { it.written || compiling.values.any { other -> other.ended != null } }

/**
 * Code the expander doesn't follow may have written each of [modules], being compiled: none of their attributes or
 * definitions is read from the tables after it, and what each defines isn't known.
 */
internal fun mayHaveWritten(modules: Collection<Compiling>) {
    for (module in modules) {
        module.attributes.apply(Effect.UnknownEffect, statement = false)
        module.written = true
    }
}

/**
 * `Module.defines?/2,3` of this module, being compiled, its table Elixir's at this point ([compilingKnown]): whether
 * its table holds [nameArity], of [kind] where one is given, or `null` where Elixir's table may differ. It may for an
 * entry that may be absent in Elixir, and where none is because a `Module.make_overridable/2` Elixir may not run took
 * it out.
 */
internal fun Compiling.defines(nameArity: NameArity, kind: DefinitionTable.Kind?): Boolean? {
    val entry = table[nameArity]

    return when {
        entry == null -> false.takeIf { overridable[nameArity]?.ordered != false }
        !entry.ordered -> null
        else -> kind == null || entry.kind == kind
    }
}

private val REQUIRED = Meta.Key.Entry("required", Meta.Value.Atom("true"))

/**
 * The checks once [module]'s body has run, each reported in the module's env, or a local call's in its calling
 * definition's: how the first that raises or stops ends the module, if one does, through [ending], which makes an
 * error a stop that may raise where the module's run isn't certain.
 */
private fun postModule(
    module: Pending.Module,
    compiling: Compiling,
    tainted: Boolean,
    run: Run,
    ending: (Expansion) -> Ended?,
): Ended? {
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

                reportOrEnd(check.site, check.at, env, run)?.let { return ending(it) }
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
