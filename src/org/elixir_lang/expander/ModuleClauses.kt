package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.lowering.ElixirAst
import java.util.Collections
import java.util.IdentityHashMap

/**
 * A module's definitions' clauses, as the module loads with them.
 *
 * @property definitions each definition the module's table holds, in its order
 * @property quotes the escaped block of each `quote` a source clause holds, built when its definition expanded
 * @property file the path of the file the clauses' origins are in: the source file, or the `.beam` whose decompiled
 *   mirror a [Source.DBGI] clause's origin is in
 */
internal class ModuleClauses(
    val module: String,
    val definitions: Map<NameArity, Definition>,
    val source: Source,
    val quotes: Quotes,
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
     * The bytes these clauses retain: each clause, each node its arguments, guards, body and quotes hold, and the bytes
     * of each binary those hold, once for each array.
     */
    fun weight(): Long {
        val clauses = definitions.values.flatMap { it.clauses.orEmpty() }
        val roots = clauses.flatMap { it.args + it.guards + it.body } + quotes.blocks

        return BYTES_PER_NODE * clauses.size + weigh(roots)
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

    /** @property clauses `null` where they aren't known */
    class Definition(val kind: DefinitionTable.Kind, val clauses: List<Clause>?)

    /**
     * One clause, as `def_to_clauses/6` gives it.
     *
     * @property args without their defaults
     * @property guards each of the head's `when`s
     * @property env the env a source clause's body, which isn't expanded, is expanded in; `null` for a
     *   [Source.DBGI] clause, whose body is
     */
    class Clause(val args: List<ElixirAst>, val guards: List<ElixirAst>, val body: ElixirAst, val env: Env?)

    companion object {
        /** As measured over the clauses of the Elixir 1.20.4 apps' `.beam`s, with their mirrors released. */
        const val BYTES_PER_NODE = 192L

        private fun weigh(roots: List<ElixirAst>): Long {
            val pending = ArrayDeque(roots)
            val arrays = Collections.newSetFromMap(IdentityHashMap<ByteArray, Boolean>())
            var count = 0L
            var bytes = 0L

            while (pending.isNotEmpty()) {
                val node = pending.removeLast()
                count++
                if (node is ElixirAst.Literal.Binary && arrays.add(node.bytes)) bytes += node.bytes.size
                pending.addAll(children(node))
            }

            return BYTES_PER_NODE * count + bytes
        }

        /**
         * The clauses [module], which [compiling] compiled from [file], loads with. A definition's aren't known where
         * Elixir may not hold it, or where it is a default arity whose defaults weren't all expanded.
         */
        fun of(module: String, compiling: Compiling, file: String?): ModuleClauses =
            ModuleClauses(
                module,
                compiling.table.entries.mapValues { (nameArity, entry) ->
                    val clauses = compiling.clauses[nameArity].orEmpty()

                    Definition(entry.kind, clauses.takeIf { entry.ordered && !(entry.default && it.isEmpty()) })
                },
                Source.SOURCE,
                compiling.quotes,
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
                file,
            )
        }

        /** The `fn` clause [arrow] as `elixir_utils:extract_splat_guards/1` reads it. */
        private fun dbgiClause(arrow: ElixirAst): Clause {
            val (head, body) = (arrow as ElixirAst.Call).arguments!!
            val args = (head as ElixirAst.ListNode).elements
            val splat = args.singleOrNull()?.let(::whenArguments)

            return if (splat != null) {
                Clause(splat.dropLast(1), orGuards(splat.last()), body, null)
            } else {
                Clause(args, emptyList(), body, null)
            }
        }
    }
}
