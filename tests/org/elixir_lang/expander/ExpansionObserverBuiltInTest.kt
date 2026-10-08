package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.lowering.ElixirAst

/** The env an [ExpansionObserver] is told a node the expander built is reached in. */
class ExpansionObserverBuiltInTest : ExpanderTestCase() {
    override val function: NameArity = NameArity("f", 1)

    fun testANodeQuoteBuildsIsReachedInTheEnvOfTheFunctionItIsIn() {
        val code = "x = 1\nquote(line: x, do: unquote(x))"

        assertEquals(
            LEVELS.joinToString("\n") { "$it: f/1" },
            LEVELS.joinToString("\n") { version ->
                val functions = mutableSetOf<String>()
                val observer = object : ExpansionObserver {
                    override fun entering(node: ElixirAst, state: ExState, env: Env) {}

                    override fun builtIn(env: Env) {
                        functions.add(env.function?.let { "${it.name}/${it.arity}" } ?: "none")
                    }
                }

                expand(code, version, observer)

                "$version: " + functions.joinToString()
            }
        )
    }
}
