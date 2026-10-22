package org.elixir_lang.expander

import com.intellij.openapi.progress.ProgressManager
import org.elixir_lang.NameArity
import org.elixir_lang.lowering.ElixirAst
import java.math.BigInteger
import java.util.Collections
import org.elixir_lang.psi.Import.Term
import java.util.IdentityHashMap

/**
 * A module's definitions' clauses, as the module loads with them.
 *
 * @property definitions each definition the module's table holds, in its order
 * @property quotes the escaped block of each `quote` a source clause holds, built when its definition expanded
 * @property dispatches what each call and bitstring segment of a source clause expanded as, recorded when its
 *   definition expanded
 * @property file the path of the file the clauses' origins are in: the source file, or the `.beam` whose decompiled
 *   mirror a [Source.DBGI] clause's origin is in
 */
internal class ModuleClauses(
    val module: String,
    val definitions: Map<NameArity, Definition>,
    val source: Source,
    val quotes: Quotes,
    val dispatches: Dispatches,
    val file: String?,
) {
    enum class Source { SOURCE, DBGI }

    /** The clauses of a module the run doesn't compile; `null` where they can't be read. */
    fun interface Lookup {
        fun of(module: String): ModuleClauses?

        companion object {
            val NONE = Lookup { null }
        }
    }

    /**
     * The bytes these clauses retain: each clause, each node its arguments, guards, body and quotes hold, each node and
     * term its dispatch records hold, each variable's version, and the bytes of each binary those hold, once for each
     * array.
     */
    fun weight(): Long {
        val clauses = definitions.values.flatMap { it.clauses.orEmpty() }
        val roots = buildList {
            for (clause in clauses) {
                addAll(clause.args)
                addAll(clause.guards)
                clause.body?.let(::add)
            }
            addAll(quotes.blocks)
            addAll(dispatches.nodes)
        }

        val arrays = Collections.newSetFromMap(IdentityHashMap<ByteArray, Boolean>())

        return BYTES_PER_NODE * (clauses.size + dispatches.versions) + weigh(roots, arrays) +
            dispatches.terms.sumOf { weigh(it, arrays) }
    }

    /** Each `quote` a module's definitions hold, as the escaped block its expansion built. */
    class Quotes {
        private val byNode = IdentityHashMap<ElixirAst, ElixirAst>()

        fun put(call: ElixirAst, block: ElixirAst) {
            byNode[call] = block
        }

        fun of(call: ElixirAst): ElixirAst? = byNode[call]

        val blocks: Collection<ElixirAst> get() = byNode.values
    }

    /**
     * What each call and bitstring segment of a source clause expanded as, and the version of each variable, by its node,
     * as Elixir compiles the clause once, when its module compiles. A call with no record wasn't reached.
     */
    class Dispatches {
        private val byNode = IdentityHashMap<ElixirAst, Dispatched>()

        fun put(node: ElixirAst, dispatched: Dispatched) {
            byNode[node] = dispatched
        }

        fun of(node: ElixirAst): Dispatched? = byNode[node]

        /** Each bitstring segment's spec, kept apart: a bare segment's node can be a call or an attribute. */
        private val segmentByNode = IdentityHashMap<ElixirAst, Segment>()

        fun putSegment(node: ElixirAst, segment: Segment) {
            segmentByNode[node] = segment
        }

        fun segmentOf(node: ElixirAst): Segment? = segmentByNode[node]

        /**
         * Each node the records hold, which the clauses retain beside their own: their keys too, which are a clause's
         * nodes, so a clause overridden without `super` is retained through them.
         */
        val nodes: List<ElixirAst>
            get() = buildList {
                addAll(byNode.keys)
                addAll(segmentByNode.keys)
                addAll(versionByNode.keys)
                byNode.values.mapNotNullTo(this) { dispatched: Dispatched ->
                    when (dispatched) {
                        is Dispatched.Macro -> dispatched.output
                        is Dispatched.Fn -> dispatched.fn
                        is Dispatched.Clauses -> dispatched.options
                        is Dispatched.Remote, Dispatched.Local, is Dispatched.Super, Dispatched.Apply, is Dispatched.Value -> null
                    }
                }
                segmentByNode.values.mapNotNullTo(this, Segment::size)
            }

        /** Each term the records hold. */
        val terms: List<Term> get() = byNode.values.mapNotNull { (it as? Dispatched.Value)?.value }

        /** The version each variable was bound or read at, kept apart: a bitstring segment's node can be its variable. */
        private val versionByNode = IdentityHashMap<ElixirAst, Int>()

        fun putVersion(node: ElixirAst, version: Int) {
            versionByNode[node] = version
        }

        fun versionOf(node: ElixirAst): Int? = versionByNode[node]

        /** How many variables have a version. */
        val versions: Int get() = versionByNode.size
    }

    /** What a node of a source clause expanded as. */
    sealed interface Dispatched {
        /** A call of [module]'s function [name], after `inline/3`. */
        data class Remote(val module: String, val name: String) : Dispatched

        /** A call of a function of the clause's own module. */
        data object Local : Dispatched

        /** `super`, a call of the definition stored as [name], of [kind], for the one being defined. */
        data class Super(val kind: DefinitionTable.Kind, val name: String) : Dispatched

        /** A remote call whose receiver is known only at run time. */
        data object Apply : Dispatched

        /**
         * A call whose [output] was expanded in its place: a macro's, its output linified, `System.stacktrace()`, or a
         * signed float's literal.
         */
        class Macro(val output: ElixirAst) : Dispatched

        /** A capture `capture_expr/6` rewrote to [fn], which was expanded where the capture is. */
        class Fn(val fn: ElixirAst) : Dispatched

        /** A `case` whose clauses `rewrite_case_clauses/1` rewrote, to [options]. */
        class Clauses(val options: ElixirAst) : Dispatched

        /**
         * A form that expands to [value], which `translate/3` receives in its place: `@name` in a function, `alias`,
         * `import` and `require`, an alias, `__DIR__`, `__ENV__`, `__ENV__.key` and a signed integer.
         */
        class Value(val value: Term) : Dispatched
    }

    /**
     * A bitstring segment, with its spec built out as `expand_specs/7` builds it.
     *
     * @property type the type the segment's value and its type spec merge to
     * @property size the size spec's argument, if it has one
     * @property unit the unit spec's, if it has one
     * @property endianness `big`, `little` or `native`
     * @property pinned whether its size is its pinned value's, which `expand_specs/7` infers for a pin in a pattern
     * @property spliced whether its value, a nested bitstring, is spliced into the bitstring it is in, as
     *   `concat_or_prepend_bitstring/6` splices one
     */
    class Segment(
        val type: String,
        val size: ElixirAst?,
        val unit: BigInteger?,
        val signed: Boolean,
        val endianness: String,
        val pinned: Boolean,
        val spliced: Boolean,
    )

    /** @property clauses `null` where they aren't known */
    class Definition(val kind: DefinitionTable.Kind, val clauses: List<Clause>?)

    /**
     * One clause, as `def_to_clauses/6` gives it.
     *
     * @property args without their defaults
     * @property guards each of the head's `when`s
     * @property body `null` where the clause stopped expanding, so whether a call matches it isn't known
     */
    class Clause(val args: List<ElixirAst>, val guards: List<ElixirAst>, val body: ElixirAst?)

    companion object {
        /** As measured over the clauses of the Elixir 1.20.4 apps' `.beam`s, with their mirrors released. */
        const val BYTES_PER_NODE = 192L

        /** The terms [term] is built of, itself included, and the bytes of each binary whose array isn't in [arrays]. */
        private fun weigh(term: Term, arrays: MutableSet<ByteArray>): Long {
            ProgressManager.checkCanceled()

            return BYTES_PER_NODE + when (term) {
                is Term.List -> term.elements.sumOf { weigh(it, arrays) } + (term.tail?.let { weigh(it, arrays) } ?: 0)
                is Term.Pair -> weigh(term.first, arrays) + weigh(term.second, arrays)
                is Term.Tuple -> term.elements.sumOf { weigh(it, arrays) }
                is Term.Binary -> term.bytes?.takeIf(arrays::add)?.size?.toLong() ?: 0
                is Term.Atom, is Term.Integer, is Term.Node, Term.NonTuple, Term.Unexpanded -> 0
            }
        }

        /** The nodes under [roots], each once, and the bytes of each binary whose array isn't in [arrays]. */
        private fun weigh(roots: List<ElixirAst>, arrays: MutableSet<ByteArray>): Long {
            val pending = ArrayDeque(roots)
            val seen = Collections.newSetFromMap(IdentityHashMap<ElixirAst, Boolean>())
            var count = 0L
            var bytes = 0L

            while (pending.isNotEmpty()) {
                ProgressManager.checkCanceled()

                val node = pending.removeLast()
                if (!seen.add(node)) continue
                count++
                if (node is ElixirAst.Literal.Binary && arrays.add(node.bytes)) bytes += node.bytes.size
                pending.addAll(children(node))
            }

            return BYTES_PER_NODE * count + bytes
        }

        /**
         * The clauses [module], which [compiling] compiled from [file], loads with. A definition's aren't known where
         * Elixir may not hold it, where a definition's name isn't known, or where it is a default arity whose defaults
         * weren't all expanded.
         */
        fun of(module: String, compiling: Compiling, file: String?): ModuleClauses =
            ModuleClauses(
                module,
                compiling.table.entries.mapValues { (nameArity, entry) ->
                    val clauses = compiling.clauses[nameArity].orEmpty()

                    Definition(
                        entry.kind,
                        clauses.takeIf { compiling.table.everyNameKnown && entry.ordered && !(entry.default && it.isEmpty()) },
                    )
                },
                Source.SOURCE,
                compiling.quotes,
                compiling.dispatches,
                file,
            )

        /** [compiled]'s clauses, read from [file]; `null` where its `.beam` holds none. */
        fun of(compiled: CompiledModule, file: String?): ModuleClauses? {
            val read = compiled.bodies as? CompiledModule.Bodies.Read ?: return null
            val kinds = compiled.table.kinds

            return ModuleClauses(
                compiled.module,
                read.clauses.mapValues { (nameArity, clauses) ->
                    Definition(kinds.getValue(nameArity), clauses.map(::dbgiClause))
                },
                Source.DBGI,
                Quotes(),
                Dispatches(),
                file,
            )
        }

        /** The `fn` clause [arrow] as `elixir_utils:extract_splat_guards/1` reads it. */
        private fun dbgiClause(arrow: ElixirAst): Clause {
            val (head, body) = (arrow as ElixirAst.Call).arguments!!
            val args = (head as ElixirAst.ListNode).elements
            val splat = args.singleOrNull()?.let(::whenArguments)

            return if (splat != null) {
                Clause(splat.dropLast(1), orGuards(splat.last()), body)
            } else {
                Clause(args, emptyList(), body)
            }
        }
    }
}
