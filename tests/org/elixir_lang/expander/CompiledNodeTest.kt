package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.psi.Import.Term

/**
 * A [ElixirAst.Placeholder.Reason.Compiled] node stands for a value only a compiler can build, such as the bytes PCRE
 * compiles a regular expression to. It expands as a literal does, where a placeholder for source the lowering has no
 * rule for stops the expansion, and nothing it stands for is ever a value the expander knows.
 */
class CompiledNodeTest : ExpanderTestCase() {
    override val kernel: KernelImports = AttributeFixtures.KERNEL.let {
        KernelImports(it.functions, (it.macros + NameArity("|>", 2) + NameArity("var!", 2)).sortedWith(compareBy({ name -> name.name }, { name -> name.arity })))
    }

    override val exports: Exports = AttributeFixtures.EXPORTS

    /** The places an expansion meets a node and so can stop at it, each with the code `c` stands in. */
    private val sites = listOf(
        "an expression" to "c",
        "a list" to "[c]",
        "a pair" to "{1, c}",
        "a tuple" to "{1, 2, c}",
        "a pipe target" to "1 |> c",
        "a bitstring specifier" to "<<1::c>>",
        "a capture" to "&c",
        "a rescue" to "try do\n1\nrescue\nc -> 2\nend",
        "a quote" to "quote do: c",
        "a var! context" to "var!(x, c) = 1",
    )

    fun testASiteStopsAtAPlaceholderForSourceWithNoRule() =
        sites.forEach { (site, code) ->
            val rendered = render(code, ElixirAst.Placeholder.Reason.Error)

            assertTrue("$site: $rendered", rendered.startsWith("unported"))
        }

    fun testNoSiteStopsAtACompiledNode() =
        sites.forEach { (site, code) ->
            val rendered = render(code, ElixirAst.Placeholder.Reason.Compiled)

            assertFalse("$site: $rendered", rendered.startsWith("unported"))
        }

    /** At each site a literal there gives the expansion a compiled node does, but for the text of the node. */
    fun testACompiledNodeExpandsAsALiteralDoes() =
        sites.forEach { (site, code) ->
            val literal = code.replace(Regex("""\bc\b"""), "\"s\"")
            val level = ElixirLanguageLevel.of("1.20.4")
            val expanded = Expander.expand(lower(literal, level), ExState.empty(level), Env.empty(level, kernel), level, exports, structs)

            assertEquals(site, render(literal, expanded).replace("\"s\"", "c"), render(code, ElixirAst.Placeholder.Reason.Compiled))
        }

    fun testACompiledNodeIsAValueNoLiteralIs() {
        val value = (expansion("[c]", ElixirAst.Placeholder.Reason.Compiled) as Expansion.Expanded).value

        assertEquals(Term.List(listOf(NODE)), value)
    }

    fun testAnAttributeHoldingACompiledNodeIsNotKnown() {
        val code = "defmodule A do\n@re c\n@known 1\nend"
        val level = ElixirLanguageLevel.of("1.20.4")
        val node = placeholding(lower(code, level), "c", ElixirAst.Placeholder.Reason.Compiled)
        val module = Expander.expandFile(node, Env.empty(level, kernel), level, exports, structs).modules.single()

        assertEquals(AttributeValue.Unknown, module.attributes.final["re"])
        assertEquals(AttributeValue.Known(Term.Integer(1.toBigInteger())), module.attributes.final["known"])
    }

    private fun expansion(code: String, reason: ElixirAst.Placeholder.Reason): Expansion {
        val level = ElixirLanguageLevel.of("1.20.4")
        val node = placeholding(lower(code, level), "c", reason)

        return Expander.expand(node, ExState.empty(level), Env.empty(level, kernel), level, exports, structs)
    }

    private fun render(code: String, reason: ElixirAst.Placeholder.Reason): String = render(code, expansion(code, reason))
}
