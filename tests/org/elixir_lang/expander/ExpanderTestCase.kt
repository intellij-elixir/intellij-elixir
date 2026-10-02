package org.elixir_lang.expander

import com.intellij.openapi.application.ReadAction
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Lowering
import org.elixir_lang.parser_definition.ParsingTestCase
import org.elixir_lang.psi.ElixirFile

/** Expands snippets at chosen language levels, without Elixir, from the start of an empty module body. */
abstract class ExpanderTestCase : ParsingTestCase() {
    /** The modules the expanded snippets can load. */
    protected open val exports: Exports = NO_EXPORTS

    /** What the empty env imports from `Kernel`. */
    protected open val kernel: KernelImports = NO_KERNEL

    /** [code], lowered and expanded at [version], from the empty env and an empty [ExState]. */
    protected fun expand(code: String, version: String, observer: ExpansionObserver = ExpansionObserver.NONE) =
        ElixirLanguageLevel.of(version).let { level ->
            Expander.expand(lower(code, level), ExState.empty(level), Env.empty(level, kernel), level, exports, observer)
        }

    protected fun lower(code: String, level: ElixirLanguageLevel): ElixirAst {
        val file = createPsiFile(getTestName(false), code) as ElixirFile

        return ReadAction.computeBlocking<_, Throwable> { Lowering.lower(file, level) }
    }

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
     * error's kind and the source of its node, or the source of the node that isn't ported.
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
            expected.joinToString("\n") { (version, _) -> "$version: " + render(code, expand(code, version)) }
        )

    companion object {
        val NO_KERNEL = KernelImports(emptyList(), emptyList())

        /** No module is loaded. */
        val NO_EXPORTS = Exports { ModuleExports.Absent }

        /** The last tag of each supported minor. */
        val LEVELS = listOf(
            "1.11.4", "1.12.3", "1.13.4", "1.14.5", "1.15.8", "1.16.3", "1.17.3", "1.18.4", "1.19.5", "1.20.4",
        )
    }
}
