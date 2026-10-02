package org.elixir_lang.beam.decompiler

import org.elixir_lang.NameArity
import org.elixir_lang.beam.MacroNameArity
import org.elixir_lang.junit.UnitTestCase

/** The head each decompiler writes for a name and arity, by the language of the `.beam` it came from. */
class DefinitionHeadTest : UnitTestCase() {
    fun testHeads() {
        val expected = HEADS.joinToString("\n") { (language, name, arity, head) -> "$language $name/$arity -> $head" }
        val actual = HEADS.joinToString("\n") { (language, name, arity, _) ->
            "$language $name/$arity -> ${head(language, name, arity)}"
        }

        assertEquals(expected, actual)
    }

    private data class Head(val language: String, val name: String, val arity: Int, val head: String)

    private fun head(language: String, name: String, arity: Int): String {
        val decompiled = StringBuilder()

        decompiler(language, NameArity(name, arity))!!.append(decompiled, MacroNameArity("def", name, arity))

        return decompiled.lineSequence().first().trim().removeSuffix(" do")
    }

    private companion object {
        val HEADS = listOf(
            Head("elixir", "foo", 1, "def foo(p0)"),
            Head("elixir", "after", 1, "def unquote(:after)(p0)"),
            Head("elixir", "else", 1, "def unquote(:else)(p0)"),
            Head("elixir", "true", 0, "def unquote(:true)()"),
            Head("elixir", "nil", 0, "def unquote(:nil)()"),
            Head("elixir", "false", 6, "def unquote(:false)(p0, p1, p2, p3, p4, p5)"),
            Head("elixir", "unquote", 1, "def unquote(:unquote)(p0)"),
            Head("elixir", "unquote_splicing", 1, "def unquote(:unquote_splicing)(p0)"),
            Head("elixir", "__aliases__", 1, "def unquote(:__aliases__)(p0)"),
            Head("elixir", "__block__", 1, "def unquote(:__block__)(p0)"),
            Head("elixir", "not", 1, "def unquote(:not)(p0)"),
            Head("elixir", "~~~", 1, "def unquote(:\"~~~\")(p0)"),
            Head("elixir", "^", 2, "def unquote(:^)(p0, p1)"),
            Head("elixir", "!", 1, "def unquote(:!)(p0)"),
            Head("elixir", "..", 0, "def unquote(:..)()"),
            Head("elixir", "+", 3, "def unquote(:+)(p0, p1, p2)"),
            Head("elixir", "+", 2, "def left + right"),
            Head("elixir", "+", 1, "def (+value)"),
            Head("elixir", "in", 2, "def left in right"),
            Head("erlang", "in", 2, "def unquote(:in)(p0, p1)"),
            Head("elixir", "~=", 2, "def unquote(:\"~=\")(p0, p1)"),
            Head("erlang", "=:=", 2, "def unquote(:\"=:=\")(p0, p1)"),
            Head("elixir", "foo bar", 0, "def unquote(:\"foo bar\")()"),
            Head("elixir", "has#{x", 0, "def unquote(:\"has\\#{x\")()"),
        )
    }
}
