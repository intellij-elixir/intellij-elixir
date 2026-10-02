package org.elixir_lang.expander

import com.intellij.openapi.application.ReadAction
import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Lowering
import org.elixir_lang.lowering.Meta
import org.elixir_lang.parser_definition.ParsingTestCase
import org.elixir_lang.psi.ElixirFile

/** Expands snippets at chosen language levels, without Elixir, from the start of an empty module body. */
abstract class ExpanderTestCase : ParsingTestCase() {
    /** The modules the expanded snippets can load. */
    protected open val exports: Exports = NO_EXPORTS

    /** The structs of the modules the expanded snippets can load. */
    protected open val structs: Structs = NO_STRUCTS

    /** What the empty env imports from `Kernel`. */
    protected open val kernel: KernelImports = NO_KERNEL

    /** The module whose body the snippets are in, or `null` for none. */
    protected open val module: String? = null

    /** The modules defined before the snippets in the same context: `E`'s `context_modules`. */
    protected open val contextModules: List<String> = emptyList()

    /** The function whose body the snippets are in, or `null` for the module body. */
    protected open val function: NameArity? = null

    /** [code], lowered and expanded at [version], from the empty env and an empty [ExState]. */
    protected fun expand(code: String, version: String, observer: ExpansionObserver = ExpansionObserver.NONE) =
        expand(code, ElixirLanguageLevel.of(version), observer)

    /** [code], lowered and expanded at [level], from the empty env and an empty [ExState]. */
    protected fun expand(code: String, level: ElixirLanguageLevel, observer: ExpansionObserver = ExpansionObserver.NONE) =
        Expander.expand(
            lower(code, level),
            ExState.empty(level),
            Env.empty(level, kernel).copy(module = module, contextModules = contextModules, function = function),
            level,
            exports,
            structs,
            observer,
        )

    /** Keys added ahead of the meta of the last call or alias, in source order, whose source is each key. */
    private var keys: Map<String, List<Meta.Key>> = emptyMap()

    /** [code], lowered at [level], with the keys [withKeys] gives. */
    protected fun lower(code: String, level: ElixirLanguageLevel): ElixirAst {
        val file = createPsiFile(getTestName(false), code) as ElixirFile

        return withKeys(ReadAction.computeBlocking<_, Throwable> { Lowering.lower(file, level) }, code)
    }

    /** Runs [assertion] with each list in [keys] added to the meta of the node whose source is its key, as macro output. */
    protected fun withKeys(vararg keys: Pair<String, List<Meta.Key>>, assertion: () -> Unit) {
        this.keys = keys.toMap()
        try {
            assertion()
        } finally {
            this.keys = emptyMap()
        }
    }

    private fun withKeys(node: ElixirAst, code: String): ElixirAst {
        if (keys.isEmpty()) return node

        val remaining = keys.toMutableMap()

        // Last child first, and a node after its children, so a source written twice takes its keys where last written.
        fun rewrite(node: ElixirAst): ElixirAst =
            when (node) {
                is ElixirAst.Call -> {
                    val arguments = node.arguments?.asReversed()?.map(::rewrite)?.asReversed()
                    val callee = rewrite(node.callee)

                    val meta = withKeys(node.meta, remaining.remove(node.meta.origin.substring(code)))

                    ElixirAst.Call(meta, callee, arguments, node.context)
                }
                is ElixirAst.Alias ->
                    ElixirAst.Alias(withKeys(node.meta, remaining.remove(node.meta.origin.substring(code))), node.segments)
                is ElixirAst.Block -> ElixirAst.Block(node.meta, node.expressions.asReversed().map(::rewrite).asReversed())
                is ElixirAst.ListNode -> ElixirAst.ListNode(node.meta, node.elements.asReversed().map(::rewrite).asReversed())
                is ElixirAst.Tuple -> ElixirAst.Tuple(node.meta, node.elements.asReversed().map(::rewrite).asReversed())
                else -> node
            }

        return rewrite(node).also { assertEquals("sources with no call or alias", emptySet<String>(), remaining.keys) }
    }

    private fun withKeys(meta: Meta, keys: List<Meta.Key>?): Meta =
        keys?.let { Meta(meta.origin, meta.start, meta.end, it + meta.keys, meta.built) } ?: meta

    protected fun entry(name: String, value: Meta.Value) = Meta.Key.Entry(name, value)

    protected fun atom(name: String) = Meta.Value.Atom(name)

    /** The `counter` entry linify gives macro output in [module]. */
    protected fun counter(n: Long) = entry("counter", Meta.Value.Tuple(listOf(atom(module!!), Meta.Value.Integer(n))))

    /** [node] with each variable named [name] made a placeholder, as source the lowering has no rule for is. */
    protected fun placeholding(node: ElixirAst, name: String): ElixirAst =
        when {
            isVariable(node) && ((node as ElixirAst.Call).callee as ElixirAst.Literal.Atom).name == name ->
                ElixirAst.Placeholder(node.meta, ElixirAst.Placeholder.Reason.Error)
            node is ElixirAst.Call ->
                ElixirAst.Call(node.meta, placeholding(node.callee, name), node.arguments?.map { placeholding(it, name) })
            node is ElixirAst.Block -> ElixirAst.Block(node.meta, node.expressions.map { placeholding(it, name) })
            node is ElixirAst.ListNode -> ElixirAst.ListNode(node.meta, node.elements.map { placeholding(it, name) })
            node is ElixirAst.Tuple -> ElixirAst.Tuple(node.meta, node.elements.map { placeholding(it, name) })
            else -> node
        }

    /**
     * [expansion] as text: the read variables sorted by name with their versions and then the next version, or the
     * error's kind and the source of its node, or the source of the node that isn't ported, or the macro's dispatch and
     * the source of its call.
     */
    protected open fun render(code: String, expansion: Expansion): String =
        when (expansion) {
            is Expansion.Expanded -> {
                val state = expansion.state
                val read = state.read.entries
                    .sortedBy { it.key.name }
                    .joinToString(" ") { (variable, version) -> "${variable.name}:$version" }

                "expanded {$read} next ${state.version}"
            }
            is Expansion.Error -> "error ${expansion.kind} `${expansion.at.meta.origin.substring(code)}`"
            is Expansion.Unported -> "unported `${expansion.at.meta.origin.substring(code)}`"
            is Expansion.Opaque -> "opaque ${render(expansion.dispatch)} `${expansion.at.meta.origin.substring(code)}`"
        }

    /** [code] expands to [expected] at every level in [LEVELS]. */
    protected fun assertEvery(code: String, expected: String) =
        assertLevels(code, LEVELS.map { it to expected })

    /**
     * [code] expands to [before] at every level in [LEVELS] before [boundary], and to [from] at [boundary] and every
     * level after it.
     */
    protected fun assertSplit(code: String, boundary: String, before: String, from: String) =
        assertWindow(code, boundary, null, before, from)

    /**
     * [code] expands to [inside] at [since] and every level in [LEVELS] from it up to [removed], and to [outside] at
     * every other level, [removed] included.
     */
    protected fun assertWindow(code: String, since: String, removed: String?, outside: String, inside: String) {
        val from = ElixirLanguageLevel.of(since).elixir
        val until = removed?.let { ElixirLanguageLevel.of(it).elixir }

        assertLevels(
            code,
            (LEVELS + listOfNotNull(since, removed))
                .sortedBy { ElixirLanguageLevel.of(it).elixir }
                .map { version ->
                    val elixir = ElixirLanguageLevel.of(version).elixir

                    version to if (elixir >= from && (until == null || elixir < until)) inside else outside
                }
        )
    }

    /** Whether [version] is a release before [boundary]. */
    protected fun isBefore(version: String, boundary: String) =
        ElixirLanguageLevel.of(version).elixir < ElixirLanguageLevel.of(boundary).elixir

    /** [code] expands to what [expected] gives each of [versions]. */
    protected fun assertLevels(code: String, versions: List<String>, expected: (String) -> String) =
        assertLevels(code, versions.map { it to expected(it) })

    private fun assertLevels(code: String, expected: List<Pair<String, String>>) =
        assertEquals(
            expected.joinToString("\n") { (version, text) -> "$version: $text" },
            expected.joinToString("\n") { (version, _) -> "$version: " + expandAndRender(code, version) }
        )

    /** [code] expanded at [version], as the assertions compare it. */
    protected open fun expandAndRender(code: String, version: String): String = render(code, expand(code, version))

    companion object {
        /** [dispatch] as `kind receiver.name/arity`, the kind as Elixir's trace event names it. */
        fun render(dispatch: Dispatch): String =
            "${dispatch.kind.name.lowercase()} ${dispatch.receiver}.${dispatch.name}/${dispatch.arity}"

        val NO_KERNEL = KernelImports(emptyList(), emptyList())

        /** No module is loaded. */
        val NO_EXPORTS = Exports { ModuleExports.Absent }

        /** No module defines a struct. */
        val NO_STRUCTS = Structs { ModuleStruct.Absent }

        /** The last tag of each supported minor. */
        val LEVELS = listOf(
            "1.11.4", "1.12.3", "1.13.4", "1.14.5", "1.15.8", "1.16.3", "1.17.3", "1.18.4", "1.19.5", "1.20.4",
        )
    }
}
