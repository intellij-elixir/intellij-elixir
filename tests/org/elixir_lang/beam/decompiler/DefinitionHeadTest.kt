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
            Head("elixir", "foo", 1, "def foo(arg1)"),
            Head("elixir", "after", 1, "def unquote(:after)(arg1)"),
            Head("elixir", "else", 1, "def unquote(:else)(arg1)"),
            Head("elixir", "true", 0, "def unquote(:true)()"),
            Head("elixir", "nil", 0, "def unquote(:nil)()"),
            Head("elixir", "false", 6, "def unquote(:false)(arg1, arg2, arg3, arg4, arg5, arg6)"),
            Head("elixir", "unquote", 1, "def unquote(:unquote)(arg1)"),
            Head("elixir", "unquote_splicing", 1, "def unquote(:unquote_splicing)(arg1)"),
            Head("elixir", "__aliases__", 1, "def unquote(:__aliases__)(arg1)"),
            Head("elixir", "__block__", 1, "def unquote(:__block__)(arg1)"),
            Head("elixir", "not", 1, "def unquote(:not)(arg1)"),
            Head("elixir", "~~~", 1, "def unquote(:\"~~~\")(arg1)"),
            Head("elixir", "^", 2, "def unquote(:^)(arg1, arg2)"),
            Head("elixir", "!", 1, "def unquote(:!)(arg1)"),
            Head("elixir", "..", 0, "def unquote(:..)()"),
            Head("elixir", "+", 3, "def unquote(:+)(arg1, arg2, arg3)"),
            Head("elixir", "+", 2, "def left + right"),
            Head("elixir", "+", 1, "def (+value)"),
            Head("elixir", "in", 2, "def left in right"),
            Head("erlang", "in", 2, "def unquote(:in)(arg1, arg2)"),
            Head("elixir", "~=", 2, "def unquote(:\"~=\")(arg1, arg2)"),
            Head("erlang", "=:=", 2, "def unquote(:\"=:=\")(arg1, arg2)"),
            Head("elixir", "foo bar", 0, "def unquote(:\"foo bar\")()"),
            Head("elixir", "has#{x", 0, "def unquote(:\"has\\#{x\")()"),
        )
    }
}
