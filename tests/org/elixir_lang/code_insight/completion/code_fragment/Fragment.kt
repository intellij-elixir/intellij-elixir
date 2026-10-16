package org.elixir_lang.code_insight.completion.code_fragment

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangMap
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangString
import com.ericsson.otp.erlang.OtpErlangTuple

/**
 * The shapes `Code.Fragment.cursor_context/2` and `surround_context/3` return on Elixir 1.20.4, decoded from the
 * terms the quoter sends. A term outside them fails [decodeCursor] or [decodeSurround].
 */
sealed interface CursorContext

/** What `surround_context/3`'s `context` can be: it adds [Keyword] and [Key], and never answers [Expr]. */
sealed interface SurroundContext

/** `inside_dot`: what a dot follows. */
sealed interface Receiver

/** `inside_alias`: the expression an alias continues. */
sealed interface AliasInside

/** `inside_struct`. */
sealed interface StructInside

/** `inside_caller`: what an anonymous call calls. */
sealed interface Caller

data class Alias(val name: String) : CursorContext, SurroundContext, Receiver {
    override fun toString() = "{:alias, ${charlist(name)}}"
}

data class InsideAlias(val inside: AliasInside, val name: String) :
    CursorContext, SurroundContext, Receiver, StructInside {
    override fun toString() = "{:alias, $inside, ${charlist(name)}}"
}

data class BlockKeywordOrBinaryOperator(val hint: String) : CursorContext {
    override fun toString() = "{:block_keyword_or_binary_operator, ${charlist(hint)}}"
}

data class Dot(val receiver: Receiver, val hint: String) :
    CursorContext, SurroundContext, Receiver, AliasInside, StructInside {
    override fun toString() = "{:dot, $receiver, ${charlist(hint)}}"
}

data class DotArity(val receiver: Receiver, val hint: String) : CursorContext {
    override fun toString() = "{:dot_arity, $receiver, ${charlist(hint)}}"
}

data class DotCall(val receiver: Receiver, val hint: String) : CursorContext {
    override fun toString() = "{:dot_call, $receiver, ${charlist(hint)}}"
}

data object Expr : CursorContext, Receiver {
    override fun toString() = ":expr"
}

/** Also a [Receiver]: inside a struct, a dot after a variable names it `{:local_or_var, _}`, not `{:var, _}`. */
data class LocalOrVar(val hint: String) : CursorContext, SurroundContext, AliasInside, StructInside, Receiver {
    override fun toString() = "{:local_or_var, ${charlist(hint)}}"
}

data class Var(val name: String) : Receiver, Caller {
    override fun toString() = "{:var, ${charlist(name)}}"
}

data class LocalArity(val hint: String) : CursorContext, SurroundContext {
    override fun toString() = "{:local_arity, ${charlist(hint)}}"
}

data class LocalCall(val hint: String) : CursorContext, SurroundContext {
    override fun toString() = "{:local_call, ${charlist(hint)}}"
}

data class AnonymousCall(val caller: Caller) : CursorContext {
    override fun toString() = "{:anonymous_call, $caller}"
}

data class CaptureArg(val hint: String) : CursorContext, SurroundContext {
    override fun toString() = "{:capture_arg, ${charlist(hint)}}"
}

data class ModuleAttribute(val hint: String) :
    CursorContext, SurroundContext, Receiver, AliasInside, StructInside, Caller {
    override fun toString() = "{:module_attribute, ${charlist(hint)}}"
}

data class Operator(val operator: String) : CursorContext, SurroundContext {
    override fun toString() = "{:operator, ${charlist(operator)}}"
}

data class OperatorArity(val operator: String) : CursorContext {
    override fun toString() = "{:operator_arity, ${charlist(operator)}}"
}

data class OperatorCall(val operator: String) : CursorContext {
    override fun toString() = "{:operator_call, ${charlist(operator)}}"
}

data object None : CursorContext {
    override fun toString() = ":none"
}

data class Sigil(val hint: String) : CursorContext, SurroundContext {
    override fun toString() = "{:sigil, ${charlist(hint)}}"
}

data class Struct(val inside: StructInside) : CursorContext, SurroundContext {
    override fun toString() = "{:struct, $inside}"
}

/** A struct's static alias, which Elixir gives as a bare charlist. */
data class StructName(val name: String) : StructInside {
    override fun toString() = charlist(name)
}

data class UnquotedAtom(val hint: String) : CursorContext, SurroundContext, Receiver {
    override fun toString() = "{:unquoted_atom, ${charlist(hint)}}"
}

data class Keyword(val name: String) : SurroundContext {
    override fun toString() = "{:keyword, ${charlist(name)}}"
}

data class Key(val name: String) : SurroundContext {
    override fun toString() = "{:key, ${charlist(name)}}"
}

/** A 1-based line and column, columns counting grapheme clusters. */
data class Position(val line: Int, val column: Int) {
    override fun toString() = "{$line, $column}"
}

/** `surround_context/3`'s map. */
data class Surround(val context: SurroundContext, val begin: Position, val end: Position) {
    override fun toString() = "$context $begin-$end"
}

/** `surround_context/3`'s answer: [surround] is `null` for `:none`. */
data class SurroundAnswer(val surround: Surround?) {
    override fun toString() = surround?.toString() ?: ":none"
}

fun decodeCursor(term: OtpErlangObject): CursorContext =
    when (term) {
        EXPR -> Expr
        NONE -> None
        else -> {
            val (tag, elements) = tagged(term) ?: refuse("cursor_context", term)

            when {
                tag == "alias" && elements.size == 1 -> Alias(charlist(elements[0]) ?: refuse("cursor_context", term))
                tag == "alias" && elements.size == 2 -> insideAlias(elements, term, inStruct = false)
                tag == "dot" && elements.size == 2 -> Dot(receiver(elements[0], inStruct = false), hint(elements[1], term))
                tag == "dot_arity" && elements.size == 2 -> DotArity(receiver(elements[0], inStruct = false), hint(elements[1], term))
                tag == "dot_call" && elements.size == 2 -> DotCall(receiver(elements[0], inStruct = false), hint(elements[1], term))
                tag == "anonymous_call" && elements.size == 1 -> AnonymousCall(caller(elements[0]))
                tag == "struct" && elements.size == 1 -> Struct(structInside(elements[0]))
                elements.size == 1 -> {
                    val hint = hint(elements[0], term)

                    when (tag) {
                        "block_keyword_or_binary_operator" -> BlockKeywordOrBinaryOperator(hint)
                        "local_or_var" -> LocalOrVar(hint)
                        "local_arity" -> LocalArity(hint)
                        "local_call" -> LocalCall(hint)
                        "capture_arg" -> CaptureArg(hint)
                        "module_attribute" -> ModuleAttribute(hint)
                        "operator" -> Operator(hint)
                        "operator_arity" -> OperatorArity(hint)
                        "operator_call" -> OperatorCall(hint)
                        "sigil" -> Sigil(hint)
                        "unquoted_atom" -> UnquotedAtom(hint)
                        else -> refuse("cursor_context", term)
                    }
                }
                else -> refuse("cursor_context", term)
            }
        }
    }

fun decodeSurround(term: OtpErlangObject): SurroundAnswer {
    if (term == NONE) return SurroundAnswer(null)

    val map = term as? OtpErlangMap ?: refuse("surround_context", term)
    if (map.arity() != 3) refuse("surround_context", term)
    val context = map.get(OtpErlangAtom("context")) ?: refuse("surround_context", term)
    val begin = map.get(OtpErlangAtom("begin"))?.let(::position) ?: refuse("surround_context", term)
    val end = map.get(OtpErlangAtom("end"))?.let(::position) ?: refuse("surround_context", term)

    return SurroundAnswer(Surround(surroundContext(context), begin, end))
}

private fun surroundContext(term: OtpErlangObject): SurroundContext {
    val (tag, elements) = tagged(term) ?: refuse("surround_context's context", term)

    return when {
        tag == "alias" && elements.size == 1 -> Alias(hint(elements[0], term))
        tag == "alias" && elements.size == 2 -> insideAlias(elements, term, inStruct = false)
        // `surround_context` never answers an empty dot, struct or sigil.
        tag == "dot" && elements.size == 2 -> Dot(receiver(elements[0], inStruct = false), nonEmptyHint(elements[1], term))
        tag == "struct" && elements.size == 1 ->
            Struct(structInside(elements[0]).takeUnless { it == StructName("") } ?: refuse("surround_context's context", term))
        tag == "sigil" && elements.size == 1 -> Sigil(nonEmptyHint(elements[0], term))
        elements.size == 1 -> {
            val hint = hint(elements[0], term)

            when (tag) {
                "local_or_var" -> LocalOrVar(hint)
                "local_arity" -> LocalArity(hint)
                "local_call" -> LocalCall(hint)
                "module_attribute" -> ModuleAttribute(hint)
                "operator" -> Operator(hint)
                "unquoted_atom" -> UnquotedAtom(hint)
                "keyword" -> Keyword(hint)
                "key" -> Key(hint)
                "capture_arg" -> CaptureArg(hint)
                else -> refuse("surround_context's context", term)
            }
        }
        else -> refuse("surround_context's context", term)
    }
}

/**
 * What a dot follows. A dot names a variable `{:var, _}`, except [inStruct], where Elixir keeps the
 * `{:local_or_var, _}` the struct read (`fragment.ex:533`, `:548`).
 */
private fun receiver(term: OtpErlangObject, inStruct: Boolean): Receiver {
    if (term == EXPR) return Expr

    val (tag, elements) = tagged(term) ?: refuse("inside_dot", term)

    return when {
        tag == "alias" && elements.size == 1 -> Alias(hint(elements[0], term))
        tag == "alias" && elements.size == 2 -> insideAlias(elements, term, inStruct)
        tag == "dot" && elements.size == 2 -> Dot(receiver(elements[0], inStruct), hint(elements[1], term))
        tag == "module_attribute" && elements.size == 1 -> ModuleAttribute(hint(elements[0], term))
        tag == "unquoted_atom" && elements.size == 1 -> UnquotedAtom(hint(elements[0], term))
        tag == "var" && elements.size == 1 && !inStruct -> Var(hint(elements[0], term))
        tag == "local_or_var" && elements.size == 1 && inStruct -> LocalOrVar(hint(elements[0], term))
        else -> refuse("inside_dot", term)
    }
}

/** An alias continuing an expression; [inStruct], the expression may be a dot (`%foo.bar.Baz`, `fragment.ex:509`). */
private fun insideAlias(elements: List<OtpErlangObject>, term: OtpErlangObject, inStruct: Boolean): InsideAlias {
    val (tag, inside) = tagged(elements[0]) ?: refuse("inside_alias", term)
    val aliasInside = when {
        tag == "local_or_var" && inside.size == 1 -> LocalOrVar(hint(inside[0], term))
        tag == "module_attribute" && inside.size == 1 -> ModuleAttribute(hint(inside[0], term))
        tag == "dot" && inside.size == 2 && inStruct -> Dot(receiver(inside[0], inStruct), hint(inside[1], term))
        else -> refuse("inside_alias", term)
    }

    return InsideAlias(aliasInside, hint(elements[1], term))
}

private fun structInside(term: OtpErlangObject): StructInside {
    charlist(term)?.let { return StructName(it) }

    val (tag, elements) = tagged(term) ?: refuse("inside_struct", term)

    return when {
        tag == "alias" && elements.size == 2 -> insideAlias(elements, term, inStruct = true)
        tag == "local_or_var" && elements.size == 1 -> LocalOrVar(hint(elements[0], term))
        tag == "module_attribute" && elements.size == 1 -> ModuleAttribute(hint(elements[0], term))
        tag == "dot" && elements.size == 2 -> Dot(receiver(elements[0], inStruct = true), hint(elements[1], term))
        else -> refuse("inside_struct", term)
    }
}

private fun caller(term: OtpErlangObject): Caller {
    val (tag, elements) = tagged(term)?.takeIf { it.second.size == 1 } ?: refuse("inside_caller", term)
    val name = hint(elements[0], term)

    return when (tag) {
        "var" -> Var(name)
        "module_attribute" -> ModuleAttribute(name)
        else -> refuse("inside_caller", term)
    }
}

private fun position(term: OtpErlangObject): Position? {
    val tuple = term as? OtpErlangTuple ?: return null
    if (tuple.arity() != 2) return null
    val line = (tuple.elementAt(0) as? OtpErlangLong)?.intValue() ?: return null
    val column = (tuple.elementAt(1) as? OtpErlangLong)?.intValue() ?: return null

    return Position(line, column)
}

private fun tagged(term: OtpErlangObject): Pair<String, List<OtpErlangObject>>? {
    val tuple = term as? OtpErlangTuple ?: return null
    val tag = (tuple.elementAt(0) as? OtpErlangAtom)?.atomValue() ?: return null

    return tag to tuple.elements().drop(1)
}

private fun hint(term: OtpErlangObject, whole: OtpErlangObject): String = charlist(term) ?: refuse("charlist", whole)

private fun nonEmptyHint(term: OtpErlangObject, whole: OtpErlangObject): String =
    hint(term, whole).ifEmpty { refuse("surround_context's context", whole) }

/** A charlist's text: the quoter sends one as an [OtpErlangString] when every code point is below 256. */
fun charlist(term: OtpErlangObject): String? =
    when (term) {
        is OtpErlangString -> term.stringValue()
        is OtpErlangList ->
            if (term.lastTail != null) {
                null
            } else {
                buildString {
                    for (element in term.elements()) {
                        val codePoint = (element as? OtpErlangLong)?.takeIf { it.isLong }?.longValue() ?: return null
                        if (codePoint !in 0..Character.MAX_CODE_POINT) return null
                        appendCodePoint(codePoint.toInt())
                    }
                }
            }
        else -> null
    }

/** [text] as Elixir writes a charlist. */
fun charlist(text: String): String =
    text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t").let { "~c\"$it\"" }

private fun refuse(what: String, term: OtpErlangObject): Nothing =
    throw IllegalArgumentException("not a $what shape of Elixir 1.20.4: $term")

private val EXPR = OtpErlangAtom("expr")
private val NONE = OtpErlangAtom("none")
