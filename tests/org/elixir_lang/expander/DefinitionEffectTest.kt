package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst

/**
 * The effects a macro queues with its expansion, which the module's walk applies in statement order among the
 * definitions around them. Each `:effect_*` atom in a body queues one, standing for a macro call there.
 */
class DefinitionEffectTest : ExpanderTestCase() {
    override val exports: Exports = ModuleFixtures.EXPORTS
    override val kernel: KernelImports = ModuleFixtures.KERNEL

    private val level = ElixirLanguageLevel.of("1.20.4")

    /** What an effect applied: the definitions in the table when it ran, and then `compiling`'s own changes. */
    private class Compiled(val log: List<String>, val module: ExpansionResult)

    fun testAnEffectRunsBetweenTheDefinitionsAroundIt() {
        val compiled = compile("defmodule M do\n  def a, do: 1\n  :effect_1\n  def b, do: 2\n  :effect_2\nend")

        assertEquals(listOf("effect_1 sees [a/0]", "effect_2 sees [a/0, b/0]"), compiled.log)
    }

    fun testAnEffectInsideAClauseMakesTheDefinitionsAfterItUnordered() {
        val compiled = compile(
            "defmodule M do\n  def a, do: 1\n  case 1 do\n    _ -> :effect_1\n  end\n  def b, do: 2\nend",
        )

        assertEquals(listOf("effect_1 sees [a/0]"), compiled.log)
        assertEquals(
            listOf(true, false),
            compiled.module.units.filter { it.owner is ExpansionResult.Owner.Definition }.map { it.ordered },
        )
    }

    fun testADefinitionNotRedefinedIsStoredBackWhenTheBodyEnds() {
        val compiled = compile("defmodule M do\n  def a, do: 1\n  :remove_a\n  def b, do: 2\nend")

        assertEquals(listOf("remove_a sees [a/0]"), compiled.log)
        assertEquals(listOf("b/0", "a/0"), compiled.module.table.entries.keys.map { "${it.name}/${it.arity}" })
        assertEquals(ExpansionResult.Ended.Compiled, compiled.module.ended)
    }

    fun testADefinitionRedefinedIsNotStoredBack() {
        val compiled = compile("defmodule M do\n  def a, do: 1\n  :remove_a\n  def a, do: 2\nend")

        assertEquals(listOf("a/0"), compiled.module.table.entries.keys.map { "${it.name}/${it.arity}" })
        assertEquals(1, compiled.module.table.entries.getValue(NameArity("a", 0)).clauses)
        assertEquals(4, compiled.module.table.entries.getValue(NameArity("a", 0)).line)
    }

    fun testADefinitionRedefinedAsAMacroRaises() {
        val compiled = compile("defmodule M do\n  def a, do: 1\n  :remove_a\n  defmacro a, do: 2\nend")
        val ended = compiled.module.ended

        assertEquals("bad_kind", (ended as ExpansionResult.Ended.Raised).error.kind)
    }

    fun testAModuleThatStoppedIsNotRaisedForADefinitionRedefinedAsAMacro() {
        val compiled = compile("defmodule M do\n  def a, do: 1\n  :remove_a\n  defmacro a, do: 2\n  :stop_1\nend")

        assertTrue(compiled.module.ended is ExpansionResult.Ended.Stopped)
    }

    fun testAnEffectThatRaisesEndsTheBodyThere() {
        val compiled = compile("defmodule M do\n  def a, do: 1\n  :raise_1\n  def b, do: 2\nend")
        val ended = compiled.module.ended

        assertEquals("effect_raised", (ended as ExpansionResult.Ended.Raised).error.kind)
        assertEquals(listOf("a/0"), compiled.module.table.entries.keys.map { "${it.name}/${it.arity}" })
    }

    fun testAnEffectThatCannotBeFollowedStopsTheModule() {
        val compiled = compile("defmodule M do\n  def a, do: 1\n  :stop_1\n  def b, do: 2\nend")

        assertTrue(compiled.module.ended is ExpansionResult.Ended.Stopped)
    }

    fun testTheSliceAMacroQueuedIsReplaced() {
        val run = Run(level, ExpansionObserver.NONE, exports, structs)
        val node = lower(":ok", level)
        val queued = List(4) { Pending.Effect(node, ordered = true) { null } }

        run.pending += queued[0]
        val from = run.pending.size

        run.pending += queued[1]
        run.pending += queued[2]
        run.replacePending(from, listOf(queued[3]))

        assertEquals(listOf<Pending>(queued[0], queued[3]), run.pending)
    }

    fun testARewriteMakesItsStatementsStatementsOfAStatement() {
        val body = lower("def a, do: 1\nm()", level) as ElixirAst.Block
        val compiling = Compiling(body, level)
        val call = body.expressions[1]
        val rewrite = lower("def b, do: 2\n:ok", level) as ElixirAst.Block

        assertFalse(compiling.isStatement(rewrite.expressions[0]))

        compiling.rewrote(call, rewrite)

        assertTrue(compiling.isStatement(rewrite.expressions[0]))
        assertTrue(compiling.isStatement(rewrite.expressions[1]))
    }

    fun testARewriteOfACallThatIsNoStatementAddsNone() {
        val body = lower("def a, do: 1\nm()", level) as ElixirAst.Block
        val compiling = Compiling(body, level)
        val inner = (body.expressions[0] as ElixirAst.Call).arguments!![0]
        val rewrite = lower("def b, do: 2", level)

        compiling.rewrote(inner, rewrite)

        assertFalse(compiling.isStatement(rewrite))
    }

    /**
     * [code] expanded, with an effect queued where the body names one: `:effect_n` logs the definitions in the table
     * then, `:remove_a` also makes `a/0` overridable, `:raise_n` raises and `:stop_n` can't be followed.
     */
    private fun compile(code: String): Compiled {
        val log = mutableListOf<String>()
        lateinit var run: Run
        val observer = object : ExpansionObserver {
            override fun entering(node: ElixirAst, state: ExState, env: Env) {
                val name = (node as? ElixirAst.Literal.Atom)?.name
                    ?.takeIf { EFFECTS.any(it::startsWith) }
                    ?: return
                val ordered = run.compiling.getValue(env.module!!).isStatement(node)

                run.pending += Pending.Effect(node, ordered) { compiling ->
                    log += "$name sees ${compiling.table.entries.keys.map { "${it.name}/${it.arity}" }}"

                    if (name == "remove_a") makeOverridable(compiling, NameArity("a", 0))

                    when {
                        name.startsWith("raise_") -> Expansion.Error("effect_raised", node)
                        name.startsWith("stop_") -> Expansion.Unported(node)
                        else -> null
                    }
                }
            }
        }

        run = Run(level, observer, exports, structs)
        Expander.expand(lower(code, level), ExState.empty(level), Env.empty(level, kernel), run)

        return Compiled(log, compilePending(run).single())
    }

    private companion object {
        val EFFECTS = listOf("effect_", "remove_", "raise_", "stop_")
    }
}
