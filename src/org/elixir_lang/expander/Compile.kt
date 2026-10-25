package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangObject
import com.intellij.openapi.progress.ProgressManager
import org.elixir_lang.NameArity
import org.elixir_lang.expander.ExpansionResult.Ended
import org.elixir_lang.expander.ExpansionResult.Owner
import org.elixir_lang.expander.interpreter.Binding
import org.elixir_lang.expander.interpreter.Closure
import org.elixir_lang.expander.interpreter.Declined
import org.elixir_lang.expander.interpreter.Evaluated
import org.elixir_lang.expander.interpreter.Given
import org.elixir_lang.expander.interpreter.Interpreter
import org.elixir_lang.expander.interpreter.PartsOutcome
import org.elixir_lang.expander.interpreter.Unknown
import org.elixir_lang.expander.interpreter.anyHeld
import org.elixir_lang.expander.interpreter.atom
import org.elixir_lang.expander.interpreter.integer
import org.elixir_lang.expander.interpreter.list
import org.elixir_lang.expander.interpreter.part
import org.elixir_lang.expander.interpreter.partsOf
import org.elixir_lang.expander.interpreter.tuple
import org.elixir_lang.expander.interpreter.unknownOf
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

    val statements = Statements(level).apply { add(body) }

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

    /** The statements being run, outermost first, whose units the evaluator reaches as they run. */
    private val running = ArrayDeque<Running>()

    /**
     * A statement being run, and whether every unit the evaluator reached in it so far had Elixir's outcome; a unit
     * reached after the evaluator went on past more declines than the [continuedDeclines] when the run started may
     * never run.
     */
    class Running(val site: ElixirAst, val continuedDeclines: Int) {
        var certain = true
    }

    /**
     * [block], the run of the statement at [site], during which the units it reaches are [outcome]s of its own, started
     * after [continuedDeclines] declines the evaluator went on past.
     */
    fun <T> running(site: ElixirAst, continuedDeclines: Int, block: (Running) -> T): T {
        val statement = Running(site, continuedDeclines)

        running.addLast(statement)

        try {
            return block(statement)
        } finally {
            running.removeLast()
        }
    }

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
     * is certain at it and the raise is [raiseKnown] to be Elixir's, which a definition's store's is before the
     * callbacks it runs. Elixir ran the unit where the expander did when it is [ordered], and its outcome is Elixir's
     * when it is [known] as well, it didn't stop, and the evaluator declined none of its code and assumed no raise
     * away.
     */
    fun outcome(site: ElixirAst, raw: Expansion?, ordered: Boolean, known: Boolean, raiseKnown: Boolean = known): Expansion? {
        val expansion = raw?.certain(certainAt(site) && raiseKnown, site)
        val certain = ordered && known && expansion.runs && !declined
        val within = running.lastOrNull()

        this.ordered = this.ordered && ordered
        declined = false
        // A unit the evaluator reached inside a statement has no place of its own among the statements: its outcome is
        // the statement's.
        if (within != null) within.certain = within.certain && certain else outcomes[site] = certain

        return expansion
    }

    /**
     * A unit was reached after the evaluator had gone on past [continuedDeclines] declines in all: where that is more
     * than when the statement running now started, the unit may never run, so that statement's outcome isn't Elixir's
     * for certain. The statement running now is marked, not the one whose run registered the unit, which may have ended.
     */
    fun reachedAfter(continuedDeclines: Int) {
        running.lastOrNull()?.let { if (continuedDeclines > it.continuedDeclines) it.certain = false }
    }

    /**
     * Whether a raise at [site], a unit's node, is the body's for certain: every statement before it, and every unit
     * reached before it in the statement running it, ran with Elixir's outcome, and nothing declined or assumed a raise
     * away so far. `null` is the body's end.
     */
    fun certainAt(site: ElixirAst?): Boolean =
        known && ordered && !declined && running.all { it.certain } &&
            statements.allBefore(running.firstOrNull()?.site ?: site) { outcomes[it] }

    /** [node], code of the body, run where the body runs it, from [bindings], the body's unless a statement's run has its own. */
    fun evaluate(
        node: ElixirAst,
        state: ExState,
        env: Env,
        run: Run,
        bindings: HashMap<Binding, OtpErlangObject> = this.bindings,
    ): Evaluated = recorded(Interpreter.body(node, bindings, state, env, run), node, run)

    /**
     * The match [node], a statement of the body, run where the body runs it, binding into [bindings]. The statements of
     * its right side ran before it, so their values are taken, not run again.
     */
    fun match(
        node: ElixirAst.Call,
        state: ExState,
        env: Env,
        run: Run,
        bindings: HashMap<Binding, OtpErlangObject> = this.bindings,
    ): Evaluated {
        val (pattern, right) = node.arguments!!
        val before = HashMap(bindings)
        val evaluated = when (val value = ran(right, state, env, run, bindings)) {
            is Evaluated.Value -> recorded(Interpreter.bind(pattern, value.value, node, bindings, state, env, run), pattern, run)
            is Evaluated.Declined -> value.also { Interpreter.bindDeclined(pattern, it.holds, bindings, env, run) }
            is Evaluated.Raised -> value
        }

        if (evaluated !is Evaluated.Raised) units[node] = evaluated
        bound[node] = bindings.filter { (binding, value) -> before[binding] !== value }.values.toList()

        return evaluated
    }

    // What each match's pattern bound where the body last ran it.
    private val bound = IdentityHashMap<ElixirAst, List<OtpErlangObject>>()

    /** The values the pattern of [node], a match, bound where the body last ran it: nothing for a `_` or a literal. */
    fun boundBy(node: ElixirAst): List<OtpErlangObject> = bound[node].orEmpty()

    // The units stepped unordered for an `fn` a statement kept, each once however many statements keep the `fn`.
    private val keptUnits: MutableSet<Pending> = Collections.newSetFromMap(IdentityHashMap())

    /** Whether [unit], held by an `fn` a statement kept, is stepped unordered now: only the first statement to keep it does. */
    fun keepsOnce(unit: Pending): Boolean = keptUnits.add(unit)

    /** [node], a statement of the body whose value nothing takes, run where the body runs it, from [bindings]. */
    fun statement(
        node: ElixirAst,
        state: ExState,
        env: Env,
        run: Run,
        bindings: HashMap<Binding, OtpErlangObject> = this.bindings,
    ): Evaluated = evaluate(node, state, env, run, bindings).also { if (it !is Evaluated.Raised) units[node] = it }

    /**
     * [node]'s value, its statements run: a block's expressions are taken in turn ([partsOf]), and its value is its
     * last's; a proper list's is its elements'; a macro call's is its output's; and a match or statement the walk ran
     * gives what it gave. Code the walk ran as a unit raised nowhere the unit didn't, so declining it declines only its
     * value.
     */
    private fun ran(node: ElixirAst, state: ExState, env: Env, run: Run, bindings: HashMap<Binding, OtpErlangObject>): Evaluated {
        ProgressManager.checkCanceled()

        return when (node) {
            is ElixirAst.Block if node.expressions.isNotEmpty() -> {
                val last = node.expressions.last()
                // A definition or effect the walk ran gave no value a block before its last takes.
                val taken = node.expressions.filter { it === last || it !in outcomes || it in units }

                built(partsOf(taken) { ran(it, state, env, run, bindings).part() }) { it.last() }
            }
            is ElixirAst.ListNode if node.elements.none { isCall(it, "|", 2) } ->
                built(partsOf(node.elements) { ran(it, state, env, run, bindings).part() }) { list(it) }
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
                        ?.let { ran(it.output, state, env, run, bindings) }
                        ?: evaluate(node, state, env, run, bindings)
            }
        }
    }

    /** What a value's parts gave ([outcome]), as running code gives: [value] of their values, or their decline or raise. */
    private fun built(outcome: PartsOutcome<Evaluated.Raised>, value: (List<OtpErlangObject>) -> OtpErlangObject): Evaluated =
        when (outcome) {
            is PartsOutcome.Values -> Evaluated.Value(value(outcome.values))
            is PartsOutcome.Declines -> Evaluated.Declined(outcome.declined.given, outcome.declined.holds, outcome.declined)
            is PartsOutcome.Raises -> outcome.raise
        }

    /** `elixir_quote:[function]/n` of [args], which the escape of a definition at [node] calls. */
    fun quoteHelper(function: String, args: List<OtpErlangObject>, node: ElixirAst, state: ExState, env: Env, run: Run) =
        recorded(Interpreter.quoteHelper(function, args, node, state, env, run), node, run)

    private fun recorded(evaluated: Evaluated, node: ElixirAst, run: Run): Evaluated =
        evaluated.also {
            if (it is Evaluated.Declined && !run.isPorted(node)) {
                // Inside a statement's run the decline is the statement's, whose outcome is read once its run ends.
                val within = running.lastOrNull()

                if (within != null) within.certain = false else declined = true
                mayHaveWritten(it.given.of(run.compiling))
            }
        }
}

/**
 * The statements of a module body or of the file's top level, in the order the body runs them: what runs once, where
 * the body does. A match runs the statements of its right side before it binds, and a macro's output runs in place of
 * its call.
 */
internal class Statements(private val level: ElixirLanguageLevel) {
    /** The statements on the statement path. */
    private val contained: MutableSet<ElixirAst> = Collections.newSetFromMap(IdentityHashMap())

    /** The statements each match or rewritten call runs, in order. */
    private val children = IdentityHashMap<ElixirAst, MutableList<ElixirAst>>()

    /** Each statement whose value another takes, as a match takes its right side's, to that statement, in either tier. */
    private val takenBy = IdentityHashMap<ElixirAst, ElixirAst>()

    /** The statements at the top, in order. */
    private val top = mutableListOf<ElixirAst>()

    /** The statements whose value nothing takes, which only the walk's units run. */
    private val whole: MutableSet<ElixirAst> = Collections.newSetFromMap(IdentityHashMap())

    /** The calls a macro's output was expanded in place of. */
    private val rewritten: MutableSet<ElixirAst> = Collections.newSetFromMap(IdentityHashMap())

    /** The statements that raise nowhere: their value is known, or a port that ports its raises itself built them. */
    private val inert: MutableSet<ElixirAst> = Collections.newSetFromMap(IdentityHashMap())

    /**
     * The statements of the bodies the walk follows as a statement runs: a `for`'s, a `case` clause's, and those of an
     * `fn` a call is given. Each runs where the body runs it, as many times as it does, so none is on the statement path.
     */
    private val followed: MutableSet<ElixirAst> = Collections.newSetFromMap(IdentityHashMap())

    /**
     * Each followed statement in the body of an `fn` to that `fn`, which runs it only where the `fn` is called, and each
     * such `fn` to the `fn` whose body holds it.
     */
    private val holders = IdentityHashMap<ElixirAst, ElixirAst>()

    /** Whether [node] is a statement of the body, on the statement path. */
    fun contains(node: ElixirAst): Boolean = node in contained

    /** Whether [node] is a statement of a body the walk follows as a statement runs. */
    fun follows(node: ElixirAst): Boolean = node in followed

    /** The `fn` whose body holds the followed statement [node], or `null` where a `for` or a `case` runs it. */
    fun holderOf(node: ElixirAst): ElixirAst? = holders[node]

    /** Whether one of [fns] holds the followed statement [node], in its body or an `fn`'s body in it. */
    fun heldBy(node: ElixirAst, fns: Set<ElixirAst>): Boolean {
        var holder = holders[node]

        while (holder != null) {
            ProgressManager.checkCanceled()

            if (holder in fns) return true
            holder = holders[holder]
        }

        return false
    }

    /** [node], a statement, raises nowhere, so no unit need run it. */
    fun inert(node: ElixirAst) {
        inert += node
    }

    /** Whether [node] is a statement whose value nothing takes, as a match's right side's is taken. */
    fun isWhole(node: ElixirAst): Boolean = node in whole

    /**
     * Whether the value of [node], a statement, reaches code that may keep it: something takes it, other than a match
     * that discards it (`_ = ...`) or a rewritten call, whose value is its output's, however many of either are around
     * it.
     */
    fun keeps(node: ElixirAst): Boolean {
        var statement = node

        while (statement !in whole) {
            ProgressManager.checkCanceled()

            val taker = takenBy[statement] ?: return true

            if (taker !in rewritten && (!isCall(taker, "=", 2) || !isUnderscore((taker as ElixirAst.Call).arguments!![0]))) return true
            statement = taker
        }

        return false
    }

    /** Whether a macro's output was expanded in place of [node], whose statements are its own. */
    fun isRewritten(node: ElixirAst): Boolean = node in rewritten

    /** [rewrite], a macro's output in place of [call], whose statements are statements of [call]'s tier when it is one. */
    fun rewrote(call: ElixirAst, rewrite: ElixirAst) {
        when {
            contains(call) -> {
                rewritten += call
                add(rewrite, isWhole(call), call)
            }
            follows(call) -> {
                rewritten += call
                add(rewrite, isWhole(call), call, followed = true, holder = holders[call])
            }
        }
    }

    /**
     * A block's expressions are statements, as `expand_block/5` runs them: a `_ = for` before the last is its `for`, and
     * where the block's value is taken, as a match takes its right side's, only its last expression's is. So are the
     * elements of a statement that is a list, as `defimpl`'s output for a list of types is, and the head and tail of
     * its `[h | t]`, and what a statement that is a match binds, within it: each runs where the statement does. The
     * bodies the statement runs where it does, as many times as it does, are [followed] ([followedBodies]), each in the
     * `fn` that [holder] names where one holds it.
     */
    fun add(node: ElixirAst, isWhole: Boolean = true, within: ElixirAst? = null, followed: Boolean = false, holder: ElixirAst? = null) {
        ProgressManager.checkCanceled()

        when (node) {
            is ElixirAst.Block -> {
                val last = node.expressions.lastOrNull()

                for (expression in node.expressions) {
                    add((if (expression !== last) discardedFor(expression) else null) ?: expression, isWhole || expression !== last, within, followed, holder)
                }
            }
            is ElixirAst.ListNode -> for (element in node.elements) {
                if (isCall(element, "|", 2)) {
                    (element as ElixirAst.Call).arguments!!.forEach { add(it, isWhole, within, followed, holder) }
                } else {
                    add(element, isWhole, within, followed, holder)
                }
            }
            else -> {
                within?.let { takenBy[node] = it }
                if (followed) {
                    this.followed += node
                    holder?.let { holders[node] = it }
                } else {
                    contained += node
                    (within?.let { children.getOrPut(it) { mutableListOf() } } ?: top) += node
                }
                if (isWhole) whole += node

                if (isCall(node, "=", 2)) {
                    add((node as ElixirAst.Call).arguments!![1], isWhole = false, within = node, followed = followed, holder = holder)
                }
                followedBodies(node).forEach { (body, fn) ->
                    if (fn != null && holder != null) holders[fn] = holder
                    add(body, followed = true, holder = fn ?: holder)
                }
            }
        }
    }

    /**
     * The bodies of [node] the walk follows as [node] runs, which [add] enters: a `for`'s `do`, or each clause's body
     * where the `do` has clauses; each clause's body of a `case`, which `if` and `unless` expand to; and each clause's
     * body of an `fn` a call is given, with that `fn`, which runs it only where the `fn` is called. A `def`'s body runs
     * where it is called, so it isn't entered, nor is a `cond`'s, `with`'s, `receive`'s or `try`'s.
     */
    private fun followedBodies(node: ElixirAst): List<Pair<ElixirAst, ElixirAst?>> {
        if (node !is ElixirAst.Call) return emptyList()

        val arguments = node.arguments.orEmpty()

        return when {
            isFor(node) -> doBodies(forOptions(arguments).second).map { it to null }
            isCall(node, "case", 2) -> doBodies((arguments[1] as? ElixirAst.ListNode)?.elements.orEmpty()).map { it to null }
            isFunctionCall(node) ->
                arguments.filter { isNamedCall(it, "fn") }.flatMap { fn -> clauseBodies((fn as ElixirAst.Call).arguments.orEmpty()).map { it to fn } }
            else -> emptyList()
        }
    }

    /** Whether [call] calls a function, remote or local, which may run an `fn` it is given: a special form or an operator is neither. */
    private fun isFunctionCall(call: ElixirAst.Call): Boolean =
        when (val callee = call.callee) {
            is ElixirAst.Call -> isCall(callee, ".", 2)
            is ElixirAst.Literal.Atom -> !specialForm(callee.name, call.arguments.orEmpty().size, level) && !operator(callee.name)
            else -> false
        }

    /** The bodies the `do:` of [options] runs: each clause's where it is a list of clauses, else the one body. */
    private fun doBodies(options: List<ElixirAst>): List<ElixirAst> {
        val body = options.firstOrNull { keyOf(it) == "do" }?.let { (it as ElixirAst.Tuple).elements[1] } ?: return emptyList()
        val arrows = (body as? ElixirAst.ListNode)?.elements?.takeIf { it.isNotEmpty() }

        return arrows?.let { clauseBodies(it) }?.ifEmpty { null } ?: listOf(body)
    }

    /** The body of each `->` of [arrows], where every one is a `->`. */
    private fun clauseBodies(arrows: List<ElixirAst>): List<ElixirAst> =
        if (arrows.all { isCall(it, "->", 2) }) arrows.map { (it as ElixirAst.Call).arguments!![1] } else emptyList()

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
     * @property followed whether [node] is in a body the walk follows, which stores it once per run of the body
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
        val followed: Boolean = false,
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
        val apply: (Compiling, Boolean, HashMap<Binding, OtpErlangObject>) -> Expansion?,
    ) : Pending

    /**
     * A module.
     *
     * @property node the `defmodule` call
     * @property name the module's name as atom text, or, where it isn't an atom, as `inspect/1` shows it
     * @property isAtom whether the name is an atom
     * @property env the env its body starts from
     * @property state the state its body starts from
     * @property applied the `@before_compile` callbacks, by module and function, that the macro that made the module
     *   applies itself, because it knows what they do
     * @property injectedBy the call whose evaluated output wrote the `defmodule`, which injects what its body defines
     * @property outer each version [state] reads a variable at, to the version the body the module is in reads it at
     * @property placed whether the body the module is in was [BodyValues.placed] where its expansion reached [node]:
     *   what that expansion did after it can't change what the module defines
     * @property statement whether [node] is on the statement path of the body it is in, rather than in a body the walk
     *   follows
     */
    class Module(
        val node: ElixirAst.Call,
        val name: String,
        val isAtom: Boolean,
        val body: ElixirAst,
        val env: Env,
        val state: ExState,
        val applied: Set<Pair<String, String>> = emptySet(),
        val injectedBy: ElixirAst.Call? = null,
        val outer: Map<Int, Int> = emptyMap(),
        val placed: Boolean = true,
        val statement: Boolean = true,
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
        val raises: ((HashMap<Binding, OtpErlangObject>) -> Expansion?)? = null,
        val resolve: ((AttributeTable, HashMap<Binding, OtpErlangObject>) -> org.elixir_lang.expander.Effect)? = null,
    ) : Pending

    /**
     * A check that reads no table, run where the walk reaches [node], in a module body or the file's top level.
     *
     * @property ordered whether [node] is a statement of the body or the file
     * @property check gives `null`, or how the body ends there: the error it raises, or where it stops
     */
    class Check(val node: ElixirAst, val ordered: Boolean, val check: () -> Expansion?) : Pending

    /**
     * `=`, a statement of a module body or of the file's top level, which the walk runs, binding what it matches.
     *
     * @property statement whether [node] is on the statement path, rather than in a body the walk follows
     * @property inner the units [node]'s code holds, each run where the evaluator reaches it as [node] runs, with the
     *   bindings it has there
     */
    class Match(
        val node: ElixirAst,
        val state: ExState,
        val env: Env,
        val statement: Boolean = true,
        val inner: List<Pending> = emptyList(),
    ) : Pending

    /**
     * A statement of a module body or of the file's top level that no other unit runs and whose value isn't known, which
     * a port didn't build: the evaluator runs it where the body does. Where it declines, Elixir may have raised there,
     * so no later raise is the body's for certain.
     *
     * @property statement whether [node] is on the statement path, rather than in a body the walk follows
     * @property inner the units [node]'s code holds, each run where the evaluator reaches it as [node] runs, with the
     *   bindings it has there
     */
    class Statement(
        val node: ElixirAst,
        val state: ExState,
        val env: Env,
        val statement: Boolean = true,
        val inner: List<Pending> = emptyList(),
    ) : Pending
}

/** The units the entry runs as it does, where the evaluator reaches them. */
internal val Pending.inner: List<Pending>
    get() = when (this) {
        is Pending.Match -> inner
        is Pending.Statement -> inner
        else -> emptyList()
    }

/** Whether the entry's node is a statement of the body, a module's or the file's, whose [statements] these are. */
internal fun Pending.isStatement(statements: Statements): Boolean =
    when (this) {
        is Pending.Definition -> statement
        is Pending.Effect -> ordered
        is Pending.Check -> ordered
        is Pending.Attribute -> statement
        is Pending.Module -> statement
        is Pending.Match -> statement
        is Pending.Statement -> statement
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

/**
 * These entries with each statement whose followed bodies hold units replaced by those units: what a port's output
 * queued, as the port that runs the loop or branch itself reads it.
 */
internal fun List<Pending>.unnested(): List<Pending> = flatMap { it.inner.ifEmpty { listOf(it) } }

/** The entry as it is when its node is a statement of the module body. */
internal fun Pending.asStatement(): Pending =
    when (this) {
        is Pending.Definition ->
            Pending.Definition(kind, node, head, body, fragments, env, true, checksClauses, delegatesTo, injectedBy, followed)
        is Pending.Effect -> Pending.Effect(node, true, queued, apply)
        is Pending.Attribute -> Pending.Attribute(effect, at, true, raises, resolve)
        is Pending.Check -> Pending.Check(node, true, check)
        is Pending.Match -> Pending.Match(node, state, env, true, inner)
        is Pending.Statement -> Pending.Statement(node, state, env, true, inner)
        is Pending.Module -> Pending.Module(node, name, isAtom, body, env, state, applied, injectedBy, outer, placed, true)
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
    // How the file's run ended, where a unit ended it: where it first stopped, or at that unit's own stop or error.
    var endedAt: Expansion? = null

    /**
     * [pending] run from [bindings], the file's or those the evaluator has where it reaches the unit inside a statement.
     * A stop in a unit Elixir may have run where the walk didn't follow it is the file's at [stopsAt].
     */
    fun step(pending: Pending, bindings: HashMap<Binding, OtpErlangObject>, stopsAt: ElixirAst?): Stepped {
        ProgressManager.checkCanceled()

        val statement = pending.isStatement(values.statements)
        var ordered = true
        val stepping = Stepping()
        val (raw, assumed) = run.assuming { when (pending) {
            is Pending.Module -> {
                val result = compileModule(pending, run, values, bindings)

                modules += result

                // The file's run ends where it first stopped, or at the module's own stop where nothing stopped before.
                if (result.ended.endsRunAt(statement)) {
                    stepping.ends = true
                    endedAt = stopped ?: (result.ended as? Ended.Stopped)?.let { Expansion.Unported(it.at) }
                }

                stepping.known = result.certain || result.ended.endsRun
                null
            }
            is Pending.Match -> stepping.reached(reaching(pending, values, run, ::step, stopsAt) { matched(pending, values, run, bindings) }, pending.node)
            is Pending.Check -> {
                ordered = pending.ordered

                pending.check()
            }
            is Pending.Statement -> stepping.reached(reaching(pending, values, run, ::step, stopsAt) { ran(pending, values, run, bindings) }, pending.node)
            is Pending.Definition, is Pending.Effect, is Pending.Attribute -> null
        } }

        if (assumed) values.assumed(pending)

        val ended = values.outcome(pending.site, raw, ordered, stepping.known).stoppingAt(stopsAt)

        // Nothing after a raise at a statement runs, whether or not Elixir raised where the file stopped before it.
        if (raw is Expansion.Error && statement) {
            endedAt = stopped ?: ended

            return stepping.stepped(ends = true, endedDecline = null)
        }
        if (ended is Expansion.Unported || ended is Expansion.Opaque) stopped = stopped ?: ended

        return stepping.stepped()
    }

    for (pending in run.pending) {
        if (step(pending, values.bindings, null).ends) return Walked(endedAt, modules, true)
    }

    return Walked(stopped, modules, false)
}

/** A unit of the body whose values are [values] raised: the run of that body ends there. */
private class RunEnded(val values: BodyValues) : RuntimeException(null, null, false, false)

/**
 * What running a unit gives: whether the body's run [ends] there, the [value] its code gives the code around it, and
 * the decline that code after it in the unit raised after ([Declined.ended]), which ends whatever holds the unit.
 */
private class Stepped(val ends: Boolean, val value: OtpErlangObject, val endedDecline: Declined? = null)

/**
 * What stepping a unit records as it runs: whether Elixir's outcome there is [known], whether a unit the statement
 * reached [ends] the body's run, the [value] the unit's code gives the code around it, and the decline code after a
 * reached unit raised ([endedDecline], [Declined.ended]).
 */
private class Stepping {
    var known = true
    var ends = false
    var value: OtpErlangObject = Unknown
    var endedDecline: Declined? = null

    /** [reached], what running the statement at [node] gave, recorded: its error, where it raised. */
    fun reached(reached: Reached, node: ElixirAst): Expansion? {
        known = known && reached.certain
        ends = reached.ended
        value = reached.produced()
        endedDecline = reached.endedDecline

        return reached.error(node)
    }

    fun stepped(ends: Boolean = this.ends, endedDecline: Declined? = this.endedDecline) = Stepped(ends, value, endedDecline)
}

/**
 * What running a statement gave, or `null` where a unit it reached ended the body's run, and whether every unit it
 * reached ran with Elixir's outcome.
 */
private class Reached(private val evaluated: Evaluated?, val certain: Boolean) {
    /** Whether a unit the statement reached ended the body's run. */
    val ended: Boolean get() = evaluated == null

    /** The statement's decline where code after it raised ([Declined.ended]): a loop around the statement ends there. */
    val endedDecline: Declined? get() = (evaluated as? Evaluated.Declined)?.declined?.takeIf { it.ended }

    fun produced(): OtpErlangObject = evaluated?.produced() ?: Unknown

    fun error(node: ElixirAst): Expansion? = evaluated?.error(node)
}

/**
 * [pending], a statement, [evaluate]d with each of its [Pending.inner] units registered for the evaluator to [step]
 * where it reaches the unit's node, from the bindings it has there: as a statement where [pending] is one, else as it
 * is, and a stop in one is at [stopsAt] where that is given, the statement whose run the walk didn't follow. A unit
 * the evaluator didn't reach is dropped where the statement gave a value or raised: Elixir never ran it, unless an
 * `fn` the evaluator never called holds it, which Elixir may call later, so the unit is stepped unordered from the
 * body's own bindings. Where the statement declined, Elixir may have run each of its units any number of times with
 * values that aren't known, so every one is stepped unordered, reached or not, and a stop in one is the
 * statement's, where the expander lost Elixir's path. A unit reached after the evaluator declined an iteration of a
 * loop it went on with may never run: the statement's outcome isn't Elixir's for certain.
 */
private fun reaching(
    pending: Pending,
    values: BodyValues,
    run: Run,
    step: (Pending, HashMap<Binding, OtpErlangObject>, ElixirAst?) -> Stepped,
    stopsAt: ElixirAst?,
    evaluate: () -> Evaluated,
): Reached {
    val inner = pending.inner
    val statement = pending.isStatement(values.statements)
    val reached: MutableSet<Pending> = Collections.newSetFromMap(IdentityHashMap())
    val callbacks = IdentityHashMap<Pending, (HashMap<Binding, OtpErlangObject>) -> OtpErlangObject>()
    val outerEntered = run.entered
    // The `fn`s running when this run starts were entered for it; each it enters was entered for the runs around it.
    val entered: MutableSet<ElixirAst> = Collections.newSetFromMap<ElixirAst>(IdentityHashMap()).apply { addAll(run.running) }

    run.entered = entered
    run.reaching++

    for (unit in inner) {
        val site = unit.site

        callbacks[unit] = { bindings ->
            reached += unit
            values.reachedAfter(run.continuedDeclines)
            // The unit's own node is evaluated inside its run, not reached again.
            run.reached.remove(site)

            try {
                val stepped = step(if (statement) unit.asStatement() else unit, bindings, stopsAt)

                if (stepped.ends) throw RunEnded(values)
                // The evaluator ends the loop or block holding the unit, as it does where it ran the raise itself.
                stepped.endedDecline?.let { throw it }
                stepped.value
            } finally {
                run.reached[site] = callbacks.getValue(unit)
            }
        }
        run.reached[site] = callbacks.getValue(unit)
    }

    var certain = true
    val evaluated = try {
        values.running(pending.site, run.continuedDeclines) { running ->
            try {
                evaluate()
            } finally {
                certain = running.certain
            }
        }
    } catch (ended: RunEnded) {
        // A unit of the module around this one, reached inside it, ends that module's run, not this one's.
        if (ended.values !== values) throw ended
        null
    } finally {
        run.entered = outerEntered
        run.reaching--
        // A closure the statement gave may be called by a statement around it still running, which reaches a unit's
        // node there, so the units' callbacks stay until the outermost run ends.
        if (run.reaching == 0) run.reached.clear()
    }

    outerEntered += entered

    // An `fn` whose closure the statement keeps, in what a match binds or in its value where the code around it may
    // keep that, may be called after the statement, where the walk doesn't follow it.
    val keptIn = if (evaluated is Evaluated.Value) {
        (if (pending is Pending.Match) values.boundBy(pending.node) else emptyList()) +
            (if (values.statements.keeps(pending.site)) listOf(evaluated.value) else emptyList())
    } else {
        emptyList()
    }
    val kept = keptFns(keptIn)
    val unrun = when (evaluated) {
        is Evaluated.Declined -> inner
        is Evaluated.Value -> {
            // An `fn` the statement never called holds units Elixir may run later.
            val unentered = inner.filter { unit -> values.statements.holderOf(unit.site)?.let { unit !in reached && it !in entered } == true }
            // So does a kept `fn`, however deep in the statement the unit is.
            val held = if (kept.isEmpty()) emptyList() else inner.nested().filter { unit -> values.statements.heldBy(unit.site, kept) && values.keepsOnce(unit) }

            (unentered + held).distinct()
        }
        is Evaluated.Raised, null -> emptyList()
    }

    for (unit in unrun) {
        // A statement of a body the walk follows that holds no unit runs only where the evaluator reaches it.
        if (unit.inner.isEmpty() && !unit.isStatement(values.statements) && (unit is Pending.Match || unit is Pending.Statement)) continue
        if (step(unit, values.bindings, stopsAt ?: pending.site).ends) return Reached(null, false)
    }

    return Reached(evaluated, certain)
}

/** The `fn`s whose closures [values] hold, by their nodes. */
private fun keptFns(values: List<OtpErlangObject>): MutableSet<ElixirAst> {
    val fns: MutableSet<ElixirAst> = Collections.newSetFromMap(IdentityHashMap())

    anyHeld(values) { term ->
        (term as? Closure)?.fn?.let(fns::add)
        false
    }

    return fns
}

/** These units and the units of each, however deep. */
private fun List<Pending>.nested(): List<Pending> {
    val units = mutableListOf<Pending>()
    val pending = ArrayDeque(this)

    while (pending.isNotEmpty()) {
        ProgressManager.checkCanceled()

        val unit = pending.removeFirst()

        units += unit
        pending.addAll(unit.inner)
    }

    return units
}

/**
 * This outcome, but a stop is at [stopsAt] where that is given: the statement whose run the walk didn't follow, where
 * the expander lost Elixir's path.
 */
private fun Expansion?.stoppingAt(stopsAt: ElixirAst?): Expansion? =
    if (stopsAt != null && this is Expansion.Unported) Expansion.Unported(stopsAt) else this

/** The value the unit's code gives the code around it: a value it declined to produce isn't known. */
private fun Evaluated.produced(): OtpErlangObject =
    when (this) {
        is Evaluated.Value -> value
        is Evaluated.Declined -> unknownOf(holds)
        is Evaluated.Raised -> Unknown
    }

/** How the unit at [node] that gave this ends the body, if it does: the error it raised. */
private fun Evaluated.error(node: ElixirAst): Expansion? =
    (this as? Evaluated.Raised)?.let { Expansion.Error(it.exception, node) }

/** `store_definition/3`'s value: the name and arity the definition stored, where a named one was. */
private fun Owner.defined(): OtpErlangObject =
    (this as? Owner.Definition)?.let { owner -> owner.name?.let { tuple(atom(it), integer(owner.arity.toLong())) } } ?: Unknown

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
internal fun compileModule(
    module: Pending.Module,
    run: Run,
    enclosing: BodyValues,
    bindings: HashMap<Binding, OtpErlangObject> = enclosing.bindings,
): ExpansionResult =
    module.injectedBy?.let { call -> run.expandInjected(call) { compile(module, run, enclosing, bindings) } }
        ?: compile(module, run, enclosing, bindings)

/**
 * [match] run from [bindings], which it binds into. A value the evaluator can't produce leaves what the match binds
 * unbound.
 */
private fun matched(match: Pending.Match, values: BodyValues, run: Run, bindings: HashMap<Binding, OtpErlangObject>): Evaluated =
    values.match(match.node as ElixirAst.Call, match.state, match.env, run, bindings)

private fun ran(statement: Pending.Statement, values: BodyValues, run: Run, bindings: HashMap<Binding, OtpErlangObject>): Evaluated =
    values.statement(statement.node, statement.state, statement.env, run, bindings)

/**
 * [module] compiled where the body around it reached it, reading the variables it is given from [bindings]: the body's
 * where the `defmodule` is a statement, or those the evaluator has where it reaches the `defmodule` inside one.
 */
private fun compile(module: Pending.Module, run: Run, enclosing: BodyValues, bindings: HashMap<Binding, OtpErlangObject>): ExpansionResult {
    val name = module.name
    val statement = module.isStatement(enclosing.statements)
    val placed = statement && module.placed
    val compiling = Compiling(module.body, run.level, statement && enclosing.certainAt(module.node), placed)
    val values = compiling.values

    for ((version, outer) in module.outer) {
        bindings[Binding(outer)]?.let { values.bindings[Binding(version)] = it }
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

    /**
     * [pending] run from [bindings], the body's or those the evaluator has where it reaches the unit inside a statement,
     * queueing what it queues onto [queue]: whether the module's run ends there, and the value the unit's code gives the
     * code around it. A stop in a unit Elixir may have run where the walk didn't follow it is the module's at [stopsAt].
     */
    fun step(pending: Pending, queue: ArrayDeque<Pending>, bindings: HashMap<Binding, OtpErlangObject>, stopsAt: ElixirAst?): Stepped {
        ProgressManager.checkCanceled()

        /** [unit], reached as a statement runs, and what it queues, from the [bindings] the evaluator has there. */
        fun reach(unit: Pending, bindings: HashMap<Binding, OtpErlangObject>, stopsAt: ElixirAst?): Stepped {
            val queued = ArrayDeque<Pending>()
            val stepped = step(unit, queued, bindings, stopsAt)
            var ends = stepped.ends

            while (!ends && queued.isNotEmpty()) ends = step(queued.removeFirst(), queued, bindings, stopsAt).ends

            return Stepped(ends, stepped.value, stepped.endedDecline)
        }

        // Whether Elixir ran the unit where the expander did, and whether what it did there is known: code the unit
        // runs that the expander doesn't follow, a callback's or a nested module's, hides whether Elixir raised.
        var ordered = true
        val statement = pending.isStatement(compiling.statements)
        val stepping = Stepping()

        // The unit's record, given its expansion once classified.
        var unit: ((Expansion) -> ExpansionResult.Unit)? = null

        val (raw, assumed) = run.assuming { when (pending) {
            is Pending.Module if runEnded() -> null
            is Pending.Effect if runEnded() -> null
            is Pending.Check if runEnded() -> null
            is Pending.Definition -> stoppedIfTooDeep(pending.node) {
                val unquoted = unquoted(pending, compiling, run, evaluates = !runEnded(), bindings = bindings)

                ordered = unquoted.ordered
                stepping.known = !compiling.attributes.callsBack("on_definition")

                val (owner, stored) = storeDefinition(pending, unquoted, compiling, run, expand = !runEnded())

                compiling.storedReads(owner)

                if (stored !is Expansion.Error) {
                    takeDefinitionAttributes(pending, unquoted.head, owner as Owner.Definition, compiling)
                }

                // `run_on_definition_callbacks`: a user entry's code isn't followed, and it is given the env, so it
                // may have written any module being compiled.
                if (!stepping.known && !runEnded()) mayHaveWritten(run.compiling.values)

                if (runEnded()) {
                    null
                } else {
                    val isOrdered = values.ordered && ordered

                    unit = { ExpansionResult.Unit(owner, pending.node, isOrdered, it) }
                    if (stored is Expansion.Expanded) stepping.value = owner.defined()
                    stored
                }
            }
            is Pending.Attribute -> stoppedIfTooDeep(pending.at) {
                // A write Elixir may run any number of times, with values that aren't known, isn't evaluated.
                val raises = pending.raises?.takeIf { pending.statement && !runEnded() }?.invoke(bindings)

                if (raises != null) {
                    raises
                } else {
                    val effect =
                        pending.resolve?.takeIf { pending.statement }?.invoke(compiling.attributes, bindings) ?: pending.effect

                    compiling.effects += AttributeLog.Logged(effect, pending.at, pending.statement)

                    when (val outcome = applied(effect, pending.statement, compiling, run)) {
                        is EffectOutcome.Raises -> Expansion.Error(outcome.kind, pending.at)
                        // Whether Elixir raises there isn't known, so nothing after it can be compared.
                        EffectOutcome.Unchecked -> Expansion.Unported(pending.at)
                        EffectOutcome.Stored -> null
                    }
                }
            }
            is Pending.Match -> if (runEnded()) {
                null
            } else {
                stepping.reached(reaching(pending, values, run, ::reach, stopsAt) { matched(pending, values, run, bindings) }, pending.node)
            }
            is Pending.Check -> {
                ordered = pending.ordered

                pending.check()
            }
            is Pending.Statement -> if (runEnded()) {
                null
            } else {
                stepping.reached(reaching(pending, values, run, ::reach, stopsAt) { ran(pending, values, run, bindings) }, pending.node)
            }
            is Pending.Effect -> stoppedIfTooDeep(pending.node) {
                ordered = pending.ordered

                val applied = pending.apply(compiling, pending.ordered, bindings)

                (applied as? Expansion.Opaque)?.let(effectOpaque::add)
                queue.addAll(0, pending.queued())
                applied
            }
            is Pending.Module -> {
                errors += run.errors.subList(errorsFrom, run.errors.size)
                warnings += run.warnings.subList(warningsFrom, run.warnings.size)

                val result = compileModule(pending, run, values, bindings)

                errorsFrom = run.errors.size
                warningsFrom = run.warnings.size
                nested += result
                stepping.known = result.certain || result.ended.endsRun
                stepping.ends = result.ended.endsRunAt(statement)
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
        // A definition's store raises before it runs the `@on_definition` callbacks, whose code isn't followed.
        val raiseKnown = stepping.known || pending is Pending.Definition
        val expansion = values.outcome(pending.site, raw, ordered, stepping.known, raiseKnown).stoppingAt(stopsAt)
        val unitEnded = expansion?.let { ended(it, run) }

        unit?.let { units += it(expansion!!) }
        compiling.ended = compiling.ended ?: unitEnded

        // A raise at a statement from certain inputs ends the module body's run, even after an earlier unit stopped:
        // Elixir raised there or before it, so a module that stopped may raise.
        if (statement && raw is Expansion.Error && inputsCertain || stepping.ends) {
            compiling.ended = (compiling.ended as? Ended.Stopped)?.copy(mayRaise = true) ?: compiling.ended

            return stepping.stepped(ends = true, endedDecline = null)
        }

        return stepping.stepped(ends = false)
    }

    /** Each of [queued] in the order the body runs them, until one raises. */
    fun walk(queued: List<Pending>) {
        val queue = ArrayDeque(queued)

        while (queue.isNotEmpty()) {
            if (step(queue.removeFirst(), queue, values.bindings, null).ends) return
        }
    }

    /** The module's compilation is over: the run compiles what was around it again. */
    fun leave() {
        run.pending = enclosingPending

        if (enclosing == null) run.compiling.remove(name) else run.compiling[name] = enclosing
    }

    try {
        walk(run.pending)
    } catch (ended: RunEnded) {
        // A unit of this module's body raised where no run of the body was on the stack, called from a nested module or
        // a definition's fragment while a run around the module kept its callback: the body's run ends at the raise. A
        // unit of the file or of a module around this one raised while a statement of this one ran it: Elixir raised
        // there, while compiling this module, so neither is defined.
        if (ended.values !== values) {
            leave()
            throw ended
        }
    }

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

    leave()

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

    run.load(result, placed) { ModuleClauses.of(result.module, compiling, run.virtualFile) }

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
