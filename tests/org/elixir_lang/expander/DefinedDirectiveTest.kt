package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Meta
import org.elixir_lang.psi.Import.Term

/**
 * The directive `defmodule` expands to where it is called, which carries the module it defines as `defined:` meta:
 * an `alias` up to 1.15 and a `require` from 1.16.
 */
class DefinedDirectiveTest : ExpanderTestCase() {
    fun testARequireThatDefinesAddsAContextModuleFrom1_16() =
        assertDefining("require Outer.Inner, as: Inner, warn: false", "Elixir.Outer.Inner") { version ->
            if (isBefore(version, "1.16.0-rc.0")) {
                "error unloaded_module `require Outer.Inner, as: Inner, warn: false`"
            } else {
                "context [Elixir.Outer.Inner] aliases [Elixir.Inner: Elixir.Outer.Inner] requires [] " +
                    "value Elixir.Outer.Inner"
            }
        }

    fun testARequireThatDefinesARootModuleAddsNoAliasFrom1_16() =
        assertDefining("require Foo, []", "Elixir.Foo") { version ->
            if (isBefore(version, "1.16.0-rc.0")) {
                "error unloaded_module `require Foo, []`"
            } else {
                "context [Elixir.Foo] aliases [] requires [] value Elixir.Foo"
            }
        }

    fun testAnAliasThatDefinesAddsAContextModuleUpTo1_15() =
        assertDefining("alias Outer.Inner, as: Inner, warn: false", "Elixir.Outer.Inner") { version ->
            val context = if (isBefore(version, "1.16.0-rc.0")) "Elixir.Outer.Inner" else ""

            "context [$context] aliases [Elixir.Inner: Elixir.Outer.Inner] requires [] value Elixir.Outer.Inner"
        }

    fun testAnAliasThatDefinesARootModuleRemovesItsAliasUpTo1_15() =
        assertDefining(
            "alias Elixir.Foo, as: nil, warn: false",
            "Elixir.Foo",
            listOf(Env.Alias("Elixir.Foo", "Elixir.X.Foo")),
        ) { version ->
            if (isBefore(version, "1.16.0-rc.0")) {
                "context [Elixir.Foo] aliases [] requires [] value Elixir.Foo"
            } else {
                "error invalid_alias_for_as `alias Elixir.Foo, as: nil, warn: false`"
            }
        }

    fun testAnImportThatDefinesIsCheckedBeforeTheContextModuleIsAdded() =
        assertDefining("import Foo", "Elixir.Foo") { "error unloaded_module `import Foo`" }

    override fun render(code: String, expansion: Expansion): String =
        if (expansion is Expansion.Expanded) {
            val env = expansion.env

            "context ${env.contextModules.joinToString(", ", "[", "]")} " +
                "aliases ${env.aliases.joinToString(", ", "[", "]") { "${it.alias}: ${it.module}" }} " +
                "requires ${(env.requires - DEFAULT_REQUIRES).joinToString(", ", "[", "]")} " +
                "value ${(expansion.value as? Term.Atom)?.name ?: expansion.value}"
        } else {
            super.render(code, expansion)
        }

    /**
     * [code] with `defined: [module]` on its last directive, as `defmodule` builds it, at every level in [LEVELS], from
     * the empty env with [aliases].
     */
    private fun assertDefining(
        code: String,
        module: String,
        aliases: List<Env.Alias> = emptyList(),
        expected: (String) -> String,
    ) =
        assertEquals(
            LEVELS.joinToString("\n") { "$it: ${expected(it)}" },
            LEVELS.joinToString("\n") { version ->
                val level = ElixirLanguageLevel.of(version)
                val node = defining(lower(code, level), module)
                val env = Env.empty(level, kernel).copy(aliases = aliases)
                val expansion = Expander.expand(node, ExState.empty(level), env, level, exports, structs)

                "$version: " + render(code, expansion)
            },
        )

    /** Every module the empty env requires at some level, which the rendering leaves out. */
    private val DEFAULT_REQUIRES = listOf("Elixir.Application", "Elixir.Kernel", "Elixir.Kernel.Typespec")

    private fun defining(node: ElixirAst, module: String): ElixirAst =
        when (node) {
            is ElixirAst.Block ->
                ElixirAst.Block(node.meta, node.expressions.dropLast(1) + defining(node.expressions.last(), module))
            is ElixirAst.Call -> {
                val meta = node.meta
                val keys = meta.keys + Meta.Key.Entry("defined", Meta.Value.Atom(module))

                ElixirAst.Call(Meta(meta.origin, meta.start, meta.end, keys), node.callee, node.arguments)
            }
            else -> error("not a directive: $node")
        }
}
