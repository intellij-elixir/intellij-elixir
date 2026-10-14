package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.lowering.ElixirAst

/** The node the expander built that an [ExpansionObserver] is told of, and the env it is reached in. */
class ExpansionObserverBuiltInTest : ExpanderTestCase() {
    override val function: NameArity = NameArity("f", 1)

    fun testANodeQuoteBuildsIsReachedInTheEnvOfTheFunctionItIsIn() {
        val code = "x = 1\nquote(line: x, do: unquote(x))"

        assertEquals(
            LEVELS.joinToString("\n") { "$it: f/1 `quote(line: x, do: unquote(x))`, f/1 `x`" },
            LEVELS.joinToString("\n") { version ->
                val functions = mutableSetOf<String>()
                val observer = object : ExpansionObserver {
                    override fun entering(node: ElixirAst, state: ExState, env: Env) {}

                    override fun builtIn(node: ElixirAst, env: Env) {
                        val function = env.function?.let { "${it.name}/${it.arity}" } ?: "none"
                        functions.add("$function `${node.meta.origin.substring(code)}`")
                    }
                }

                expand(code, version, observer)

                "$version: " + functions.joinToString()
            }
        )
    }
}
