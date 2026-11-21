package org.elixir_lang.lowering

import com.intellij.openapi.progress.ProgressManager
import org.elixir_lang.expander.ARGUMENTLESS_SPECS
import org.elixir_lang.expander.isCall
import org.elixir_lang.language_level.ElixirLanguageFeature.QUOTED_LITERAL_BITSTRING
import org.elixir_lang.language_level.ElixirLanguageFeature.QUOTED_LITERAL_MODULE
import org.elixir_lang.language_level.ElixirLanguageLevel

/** `Macro.quoted_literal?/1`: whether quoted code is a literal, with no call to evaluate. */
object QuotedLiteral {
    fun of(ast: ElixirAst, languageLevel: ElixirLanguageLevel): Boolean {
        ProgressManager.checkCanceled()

        return when (ast) {
            is ElixirAst.Literal -> true
            is ElixirAst.ListNode -> ast.elements.all { of(it, languageLevel) }
            is ElixirAst.Tuple -> ast.elements.all { of(it, languageLevel) }
            is ElixirAst.Alias -> ast.segments.all { of(it, languageLevel) }
            is ElixirAst.Call -> call(ast, languageLevel)
            is ElixirAst.Block, is ElixirAst.Value, is ElixirAst.Placeholder -> false
        }
    }

    private fun call(call: ElixirAst.Call, languageLevel: ElixirLanguageLevel): Boolean {
        val arguments = call.arguments

        return when ((call.callee as? ElixirAst.Literal.Atom)?.name) {
            "%" -> arguments?.size == 2 && arguments.all { of(it, languageLevel) }
            // A context in place of arguments is an atom, which is a literal.
            "%{}", "{}" -> arguments.orEmpty().all { of(it, languageLevel) }
            "__MODULE__" -> arguments == null && QUOTED_LITERAL_MODULE.isSufficient(languageLevel)
            "<<>>" -> arguments != null && QUOTED_LITERAL_BITSTRING.isSufficient(languageLevel) && arguments.all(::isSegment)
            else -> false
        }
    }

    private fun isSegment(segment: ElixirAst): Boolean =
        isIntegerOrBinary(segment) ||
            segment is ElixirAst.Call &&
            isCall(segment, "::", 2) &&
            isIntegerOrBinary(segment.arguments!![0]) &&
            isModifier(segment.arguments[1])

    private fun isModifier(modifier: ElixirAst): Boolean {
        ProgressManager.checkCanceled()

        if (modifier !is ElixirAst.Call) return false
        val arguments = modifier.arguments.orEmpty()

        return when ((modifier.callee as? ElixirAst.Literal.Atom)?.name) {
            "-" -> arguments.size == 2 && arguments.all(::isModifier)
            "size", "unit" -> arguments.singleOrNull() is ElixirAst.Literal.Integer
            "*" -> arguments.size == 2 && arguments.all { it is ElixirAst.Literal.Integer }
            in ARGUMENTLESS_SPECS -> arguments.isEmpty()
            else -> false
        }
    }

    private fun isIntegerOrBinary(ast: ElixirAst): Boolean =
        ast is ElixirAst.Literal.Integer || ast is ElixirAst.Literal.Binary
}
