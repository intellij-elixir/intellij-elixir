package org.elixir_lang.code_insight.completion.code_fragment

import org.elixir_lang.code_insight.completion.code_fragment.Kind.ATOM
import org.elixir_lang.code_insight.completion.code_fragment.Kind.ATTRIBUTE
import org.elixir_lang.code_insight.completion.code_fragment.Kind.CALL
import org.elixir_lang.code_insight.completion.code_fragment.Kind.FIELD
import org.elixir_lang.code_insight.completion.code_fragment.Kind.KEYWORD
import org.elixir_lang.code_insight.completion.code_fragment.Kind.MODULE
import org.elixir_lang.code_insight.completion.code_fragment.Kind.OTHER
import org.elixir_lang.code_insight.completion.code_fragment.Kind.VARIABLE
import org.elixir_lang.lowering.ElixirAst

/** What an offered item is, from its PSI element. The plugin offers no `FIELD`, `ATOM` or `KEYWORD` today. */
enum class Kind { ATOM, ATTRIBUTE, CALL, FIELD, KEYWORD, MODULE, OTHER, VARIABLE }

/**
 * One offered item: its lookup string [name], the [pattern] it matched against, and for a [Kind.CALL] the module
 * that defines it, or for a [Kind.MODULE] the module it names, as Elixir inspects it (`Kernel`, `:lists`).
 */
data class Item(val name: String, val pattern: String, val kind: Kind, val owner: String? = null)

/**
 * One node on a path to `__cursor__` or to the dummy identifier: its head, whether `__cursor__` is its only argument,
 * whether its first argument is a variable, and [slot], which of its arguments or elements holds the rest of the path,
 * `null` where a call's receiver or callee does.
 */
data class Step(val head: String, val cursorOnly: Boolean = false, val onVariable: Boolean = false, val slot: Int? = null) {
    /** The head with its slot, as the golden shows it. */
    val shown get() = "$head@${slot ?: "-"}"
}

/** Offsets `[begin, end)`. */
data class Span(val begin: Int, val end: Int)

/**
 * Elixir's answers at a point, in the forms the checks compare.
 *
 * @property span the name segment of `surround_context`'s span that holds the caret
 * @property path the steps from the root to `__cursor__`, without it; `null` for `{:error, _}`
 * @property surround `surround_context`'s context; `null` for `:none`, or where it was not asked
 * @property literal whether the text before the caret ends inside a literal
 * @property atomStart whether an atom starts at the `:` before the caret
 */
class Elixir(
    val cursor: CursorContext,
    val span: Span?,
    val path: List<Step>?,
    val surround: SurroundContext?,
    val literal: Boolean,
    val atomStart: Boolean,
)

/**
 * What basic completion did at a point.
 *
 * @property identifierEnd where Tab's replacement ends
 * @property heads the enclosing forms at the dummy identifier in completion's file copy, lowered, cursors dropped;
 *   `null` when lowering logged an error
 */
class Plugin(val caret: Int, val items: List<Item>, val identifierEnd: Int, val heads: List<Step>?)

/** The case's `alias` forms: each alias as written, to the module it names. */
class Scope(val aliases: Map<String, String> = emptyMap()) {
    fun expand(alias: String): String {
        val first = alias.substringBefore('.')

        return aliases[first]?.let { it + alias.removePrefix(first) } ?: alias
    }
}

/**
 * A check's verdict at a point. [EMPTY] is the kinds check's where its rule allows items and none was offered;
 * [NO_RULE] is where the shape states no rule, or the prefix check's with no item.
 */
enum class Verdict {
    AGREE, DIFFER, EMPTY, NO_RULE;

    override fun toString() = if (this == NO_RULE) "-" else name.lowercase()
}

/** The kinds check holds each item's kind, owner and name. */
enum class Check { PREFIX, KINDS, RANGE, CONTAINER }

/** Each check's verdict, and the failed checks with their offending values. */
class Judgement(val checks: Map<Check, Verdict>, val failed: List<String>)

/**
 * Inside a literal Elixir's answers describe the literal's text, which completion does not complete.
 *
 * @param afterWhitespace whether the character before the caret is whitespace, or there is none
 */
fun judge(elixir: Elixir, plugin: Plugin, scope: Scope, afterWhitespace: Boolean): Judgement {
    if (elixir.literal) return Judgement(Check.entries.associateWith { Verdict.NO_RULE }, emptyList())

    val rule = rule(elixir.cursor, elixir.path, scope, elixir.atomStart)
    val items = plugin.items
    val failed = mutableListOf<String>()

    val prefix = when {
        rule.prefix == null || items.isEmpty() -> Verdict.NO_RULE
        else -> failed.check("prefix", items.filter { it.pattern != rule.prefix }.map { charlist(it.pattern) })
    }
    val kinds = when {
        rule.kinds == null -> Verdict.NO_RULE
        items.isEmpty() -> Verdict.EMPTY
        else -> listOf(
            failed.check("kind", items.filter { it.kind !in rule.kinds }.map { it.kind.name.lowercase() }),
            failed.check("owner", items.filter { rule.owners[it.kind]?.invoke(it.owner) == false }.map { it.owner.toString() }),
            failed.check("name", items.filter { it.kind == CALL && rule.callName?.invoke(it.name) == false }.map { it.name }),
        ).let { if (Verdict.DIFFER in it) Verdict.DIFFER else Verdict.AGREE }
    }
    val range = elixir.span?.let { span ->
        val begin = items.any { plugin.caret - it.pattern.length != span.begin }
        val end = plugin.identifierEnd != span.end

        if (begin) failed.add("range begin")
        if (end) failed.add("range end")
        if (begin || end) Verdict.DIFFER else Verdict.AGREE
    } ?: Verdict.NO_RULE
    // At `end|` Elixir's container is outside the block `end` closes.
    val closing = elixir.surround == Keyword("end") && elixir.span?.end == plugin.caret
    // Where Elixir reads no context after a character that is not whitespace, a character typed there would join it.
    val joined = elixir.cursor == None && !afterWhitespace
    val container = elixir.path?.takeUnless { closing || joined }?.let { path ->
        val heads = plugin.heads
        val compared = compared(elixir.cursor, path)

        when {
            heads == null -> {
                failed.add("container (lowering logged)")
                Verdict.DIFFER
            }
            // A head agrees on its name and the argument holding the caret: the plugin also sees the arguments after it.
            heads.size < compared.size || compared.zip(heads).any { (elixir, plugin) ->
                name(elixir.head) != name(plugin.head) || elixir.slot != plugin.slot
            } -> {
                failed.add("container")
                Verdict.DIFFER
            }
            else -> Verdict.AGREE
        }
    } ?: Verdict.NO_RULE

    return Judgement(
        mapOf(Check.PREFIX to prefix, Check.KINDS to kinds, Check.RANGE to range, Check.CONTAINER to container),
        failed,
    )
}

/** Adds [check] with the first few distinct [offending] values, when there are any, and answers its verdict. */
private fun MutableList<String>.check(check: String, offending: List<String>): Verdict {
    val distinct = offending.distinct().sorted()

    return if (distinct.isNotEmpty()) {
        val more = if (distinct.size > SHOWN) ",+${distinct.size - SHOWN}" else ""

        add("$check(${distinct.take(SHOWN).joinToString(",")}$more)")
        Verdict.DIFFER
    } else {
        Verdict.AGREE
    }
}

/**
 * The name segment of `surround_context`'s span that holds [caret]. In [text], the last segment ends at the span's
 * `end`, and each earlier one before a `.`, with only whitespace around it. Elixir's `begin` is not read: after an
 * `:expr` receiver it is the `end`. `null` where no segment holds the caret, as at `&|+/2`, where Elixir answers the
 * `/`, or where [text] does not hold the segments there, as for the `...` Elixir finds across the joined lines `...`
 * and `..`.
 */
fun span(surround: Surround, offset: (Position) -> Int, caret: Int, text: String): Span? {
    var end = offset(surround.end)
    val spans = segments(surround.context).asReversed().mapIndexed { index, segment ->
        if (index > 0) end = beforeDot(text, end) ?: return null

        val begin = end - segment.length

        if (begin < 0 || !text.startsWith(segment, begin)) return null
        Span(begin, end).also { end = begin }
    }

    return spans.firstOrNull { caret in it.begin..it.end }
}

/** Where the segment before [end]'s `.` ends, or `null` where [text] has no `.` there, whitespace aside. */
private fun beforeDot(text: String, end: Int): Int? {
    var at = end

    while (at > 0 && text[at - 1].isWhitespace()) at--
    if (at == 0 || text[at - 1] != '.') return null
    at--
    while (at > 0 && text[at - 1].isWhitespace()) at--

    return at
}

/**
 * What Elixir's rule asks of every item. `null` states no rule. [owners] applies to the items of its kinds, and
 * [callName] to the calls.
 */
private class Rule(
    val prefix: String?,
    val kinds: Set<Kind>?,
    val owners: Map<Kind, (String?) -> Boolean> = emptyMap(),
    val callName: ((String) -> Boolean)? = null,
)

private fun rule(cursor: CursorContext, path: List<Step>?, scope: Scope, atomStart: Boolean): Rule =
    when (cursor) {
        is Alias -> Rule(lastSegment(cursor.name), setOf(MODULE), mapOf(MODULE to namedUnderParent(cursor.name, scope)))
        is InsideAlias -> Rule(lastSegment(cursor.name), setOf(MODULE))
        is BlockKeywordOrBinaryOperator -> Rule(cursor.hint, setOf(KEYWORD, CALL))
        // IEx offers what the variable holds at runtime.
        is Dot -> if (rootedAtVariable(cursor)) Rule(cursor.hint, null) else dot(cursor, path, scope)
        is DotArity -> Rule(cursor.hint, setOf(CALL), callOwner(cursor.receiver, scope)) { it == cursor.hint }
        is DotCall, is AnonymousCall -> Rule("", setOf(VARIABLE, CALL))
        Expr -> expr(path)
        is LocalOrVar -> Rule(cursor.hint, setOf(CALL, VARIABLE) + fields(path))
        is LocalArity -> Rule(cursor.hint, setOf(CALL)) { it == cursor.hint }
        is LocalCall -> if (cursor.hint in ALIAS_ONLY) Rule("", setOf(MODULE)) else Rule("", setOf(VARIABLE, CALL))
        is CaptureArg -> Rule(null, null)
        is ModuleAttribute -> Rule(cursor.hint, setOf(ATTRIBUTE))
        is Operator ->
            if (cursor.operator in BITSTRING_MODIFIER_OPERATORS && bitstringModifier(path)) {
                Rule("", setOf(OTHER))
            } else {
                Rule(cursor.operator, setOf(CALL)) { it.startsWith(cursor.operator) }
            }
        is OperatorArity -> Rule(cursor.operator, setOf(CALL)) { it == cursor.operator }
        is OperatorCall -> if (cursor.operator == "|") expr(path) else Rule("", setOf(VARIABLE, CALL))
        // An explicit request completes from an empty prefix, and Elixir names no kind to offer.
        None -> Rule("", null)
        is Sigil -> Rule("", setOf(if (cursor.hint.isEmpty()) CALL else OTHER))
        is Struct -> struct(cursor.inside, path, scope)
        // `cursor_context` reads `key:|` and `t :|:` as an atom's start: it sees no text after the caret.
        is UnquotedAtom -> if (cursor.hint.isEmpty() && !atomStart) Rule(null, null) else Rule(cursor.hint, setOf(MODULE, ATOM))
    }

private fun rootedAtVariable(dot: Dot): Boolean =
    when (val receiver = dot.receiver) {
        is Var -> true
        is Dot -> rootedAtVariable(receiver)
        is Alias, Expr, is InsideAlias, is LocalOrVar, is ModuleAttribute, is UnquotedAtom -> false
    }

private fun dot(cursor: Dot, path: List<Step>?, scope: Scope): Rule =
    when (val receiver = cursor.receiver) {
        is Alias -> {
            val module = scope.expand(receiver.name)

            when {
                cursor.hint.isNotEmpty() -> Rule(cursor.hint, setOf(CALL), mapOf(CALL to { it == module }))
                aliasOnly(path) -> Rule("", setOf(MODULE), mapOf(MODULE to under(module)))
                else -> Rule("", setOf(MODULE, CALL), mapOf(MODULE to under(module), CALL to { it == module }))
            }
        }
        is UnquotedAtom -> Rule(cursor.hint, setOf(CALL), mapOf(CALL to { it == ":${receiver.hint}" }))
        is Var, is LocalOrVar, is ModuleAttribute, is Dot ->
            if (cursor.hint.isEmpty()) Rule("", setOf(FIELD, CALL, MODULE)) else Rule(cursor.hint, setOf(FIELD, CALL))
        Expr, is InsideAlias -> Rule(cursor.hint, null)
    }

private fun struct(inside: StructInside, path: List<Step>?, scope: Scope): Rule =
    when (inside) {
        is StructName -> Rule(lastSegment(inside.name), setOf(MODULE))
        is InsideAlias -> Rule(lastSegment(inside.name), setOf(MODULE))
        is LocalOrVar -> Rule(inside.hint, setOf(VARIABLE, CALL))
        is ModuleAttribute -> Rule(inside.hint, setOf(ATTRIBUTE))
        is Dot -> {
            val receiver = inside.receiver

            if (receiver is Alias && inside.hint.isEmpty()) {
                Rule("", setOf(MODULE), mapOf(MODULE to under(scope.expand(receiver.name))))
            } else {
                dot(inside, path, scope)
            }
        }
    }

private fun expr(path: List<Step>?) = Rule("", setOf(MODULE, CALL, VARIABLE) + fields(path))

private fun callOwner(receiver: Receiver, scope: Scope): Map<Kind, (String?) -> Boolean> =
    when (receiver) {
        is Alias -> scope.expand(receiver.name).let { module -> mapOf(CALL to { it == module }) }
        is UnquotedAtom -> mapOf(CALL to { it == ":${receiver.hint}" })
        is Dot, Expr, is InsideAlias, is LocalOrVar, is ModuleAttribute, is Var -> emptyMap()
    }

/** IEx lists the aliases and the top-level modules for an alias with no parent. */
private fun namedUnderParent(alias: String, scope: Scope): (String?) -> Boolean {
    val parent = alias.substringBeforeLast('.', "")

    return if (parent.isEmpty()) {
        { owner -> owner != null && (owner in scope.aliases.values || owner.firstOrNull()?.isUpperCase() == true && '.' !in owner) }
    } else {
        under(scope.expand(parent))
    }
}

private fun under(module: String): (String?) -> Boolean = { owner -> owner?.startsWith("$module.") == true }

private fun lastSegment(alias: String) = alias.substringAfterLast('.')

/**
 * IEx offers fields where the cursor is an element of a struct's `%{}`, or of the `[]` after the `|` of a struct's
 * update or of a variable's (`autocomplete.ex:404-419`); never in a plain map literal.
 */
private fun fields(path: List<Step>?): Set<Kind> {
    val steps = path.orEmpty()
    val heads = steps.map(Step::head)
    val struct = heads.takeLast(2).let { it.size == 2 && it[0] == "%/2" && it[1].startsWith("%{}/") }
    val update = heads.takeLast(3).let { it.size == 3 && it[0].startsWith("%{}/") && it[1] == "|/2" && it[2] == "[]" } &&
        (steps[steps.size - 2].onVariable || heads.getOrNull(heads.size - 4) == "%/2")

    return if (struct || update) setOf(FIELD) else emptySet()
}

/**
 * IEx offers only bitstring modifiers where the cursor's parents, binary `-` removed, are `::/2` directly in `<<>>`
 * (`autocomplete.ex:424-427`, `:439-443`).
 */
private fun bitstringModifier(path: List<Step>?): Boolean {
    val heads = path.orEmpty().map(Step::head).dropLastWhile { it == "-/2" }

    return heads.size >= 2 && heads.last() == "::/2" && heads[heads.size - 2].startsWith("<<>>/")
}

private fun aliasOnly(path: List<Step>?) = path?.lastOrNull()?.let { it.cursorOnly && it.head in ALIAS_ONLY_HEADS } == true

/**
 * Elixir's heads with the call its pruning builds dropped: after a receiver that is a variable, an attribute, a dot
 * or an alias with an inside, the cursor parses as the receiver's no-parens argument. After an attribute that call
 * is the attribute's name, inside `@`.
 */
fun compared(cursor: CursorContext, path: List<Step>): List<Step> {
    val receiver = when (cursor) {
        is Dot -> cursor.receiver
        is InsideAlias -> when (val inside = cursor.inside) {
            is LocalOrVar -> inside
            is ModuleAttribute -> inside
            is Dot -> inside
        }
        else -> null
    }
    val root = (receiver as? InsideAlias)?.inside ?: receiver
    val kept =
        if (receiver != null && prunes(receiver) && path.lastOrNull()?.cursorOnly == true) {
            path.dropLast(1).let { if (root is ModuleAttribute && it.lastOrNull()?.head == "@/1") it.dropLast(1) else it }
        } else {
            path
        }

    return kept
}

private fun prunes(receiver: Receiver): Boolean =
    when (receiver) {
        is Dot, is InsideAlias, is LocalOrVar, is ModuleAttribute, is Var -> true
        is Alias, Expr, is UnquotedAtom -> false
    }

/** The name segments of [context], in order; the last ends at `surround_context`'s `end`. */
fun segments(context: SurroundContext): List<String> =
    when (context) {
        is Alias -> context.name.split('.')
        is InsideAlias -> receiverSegments(context)
        is Dot -> receiverSegments(context)
        is CaptureArg -> listOf(context.hint)
        is Key -> listOf(context.name)
        is Keyword -> listOf(context.name)
        is LocalArity -> listOf(context.hint)
        is LocalCall -> listOf(context.hint)
        is LocalOrVar -> listOf(context.hint)
        is ModuleAttribute -> listOf(context.hint)
        is Operator -> listOf(context.operator)
        is Sigil -> listOf(context.hint)
        is Struct -> when (val inside = context.inside) {
            is StructName -> inside.name.split('.')
            is InsideAlias -> receiverSegments(inside)
            is Dot -> receiverSegments(inside)
            is LocalOrVar -> listOf(inside.hint)
            is ModuleAttribute -> listOf(inside.hint)
        }
        is UnquotedAtom -> listOf(context.hint)
    }

private fun receiverSegments(receiver: Receiver): List<String> =
    when (receiver) {
        is Alias -> receiver.name.split('.')
        is InsideAlias -> when (val inside = receiver.inside) {
            is Dot -> receiverSegments(inside)
            is LocalOrVar -> listOf(inside.hint)
            is ModuleAttribute -> listOf(inside.hint)
        } + receiver.name.split('.')
        is Dot -> receiverSegments(receiver.receiver) + receiver.hint
        is LocalOrVar -> listOf(receiver.hint)
        is ModuleAttribute -> listOf(receiver.hint)
        is UnquotedAtom -> listOf(receiver.hint)
        is Var -> listOf(receiver.name)
        Expr -> emptyList()
    }

/** The steps from [root] to its `__cursor__`. */
fun path(root: ElixirAst): List<Step> =
    toCursor(root).zipWithNext { node, next ->
        head(node)?.let {
            val arguments = (node as? ElixirAst.Call)?.arguments

            Step(
                it,
                arguments?.singleOrNull()?.let(::isCursor) == true,
                arguments?.firstOrNull()?.let(::isVariable) == true,
                slot(node, next),
            )
        }
    }.filterNotNull()

/** The nodes from [root] to its `__cursor__`, both included. */
private fun toCursor(root: ElixirAst): List<ElixirAst> =
    requireNotNull(pathTo(root, ::isCursor)) { "no __cursor__ in Elixir's container" }

/** The nodes from [root] to the first node, depth first, that is [found], both included. */
private fun pathTo(root: ElixirAst, found: (ElixirAst) -> Boolean): List<ElixirAst>? =
    if (found(root)) listOf(root) else children(root).firstNotNullOfOrNull { pathTo(it, found) }?.let { listOf(root) + it }

/**
 * The heads from [root] to the node holding the [dummy] identifier that completion inserted at [caret], without that
 * node or any cursor. A node holds it in its own text, or as a placeholder over the caret, as recovery from the
 * dummy after a closing bracket or quote makes. Empty where no node holds it, as in a comment. A head written after
 * the caret is dropped, as no prefix holds it: the outer `-` of `a - | - b`. So is a form that a token after the caret
 * makes, in [text], the completion copy's text: the clause list and argument list of a `->`, and the pair of a `=>`.
 */
fun heads(root: ElixirAst, caret: Int, dummy: String, text: String): List<Step> {
    val name = dummy.trimEnd()
    val holds = { node: ElixirAst ->
        name in ownText(node) ||
            node is ElixirAst.Placeholder && node.meta.origin.startOffset <= caret && caret < node.meta.origin.endOffset
    }
    val nodes = pathTo(root, holds).orEmpty()

    return nodes.zipWithNext().mapIndexedNotNull { index, (node, next) ->
        head(node)
            ?.takeUnless { it == CURSOR || definingToken(nodes.getOrNull(index - 1), node, next, text) >= caret }
            ?.let { Step(it, slot = slot(node, next)) }
    }
}

/**
 * Where the token that makes [node] is written in [text], on a path where [parent] is above it and [next] below. A
 * stab clause's argument list and a clause list that starts with a `->` are made by that `->`, and a `=>` pair, whose
 * key can be a literal or parenthesised, by its `=>`.
 */
private fun definingToken(parent: ElixirAst?, node: ElixirAst, next: ElixirAst, text: String): Int =
    when {
        parent != null && isStab(parent) && slot(parent, node) == 0 -> headStart(parent, text)
        node is ElixirAst.ListNode && isStab(next) && slot(node, next) == 0 -> headStart(next, text)
        node is ElixirAst.Tuple && slot(node, next) == 0 ->
            node.elements.getOrNull(1)?.let { arrowBetween(text, next.meta.origin.endOffset, it.meta.origin.startOffset) }
                ?: headStart(node, text)
        else -> headStart(node, text)
    }

/**
 * Where a `=>` is written in [text] from [from] to [to], the end of a pair's key and the start of its value, outside a
 * comment.
 */
private fun arrowBetween(text: String, from: Int, to: Int): Int? {
    if (from !in 0..to || to > text.length) return null

    var at = from

    while (at < to) {
        when {
            text.startsWith("=>", at) -> return at
            text[at] == '#' -> at = text.indexOf('\n', at).takeIf { it >= 0 } ?: to
            else -> at++
        }
    }

    return null
}

private fun isStab(node: ElixirAst): Boolean =
    node is ElixirAst.Call && (node.callee as? ElixirAst.Literal.Atom)?.name == "->"

/**
 * Where a `.` is written in [text] after [from], whitespace and the `)` that close a parenthesised form aside, or
 * `null` where something else follows.
 */
private fun dotAfter(text: String, from: Int): Int? {
    var at = from

    while (at < text.length && (text[at].isWhitespace() || text[at] == ')')) at++

    return at.takeIf { text.startsWith(".", it) }
}

/**
 * The text of [node] that [children] does not reach: a call's or a variable's name, a remote call's member, an alias's
 * atom segments, an atom's name, a binary's bytes, and a sigil's modifiers.
 */
private fun ownText(node: ElixirAst): String =
    when (node) {
        is ElixirAst.Call -> when (val callee = node.callee) {
            is ElixirAst.Literal.Atom -> callee.name + if (callee.name.startsWith("sigil_")) modifiers(node) else ""
            is ElixirAst.Call -> dotted(callee)?.second.orEmpty()
            else -> ""
        }
        is ElixirAst.Alias -> node.segments.filterIsInstance<ElixirAst.Literal.Atom>().joinToString(".") { it.name }
        is ElixirAst.Literal.Atom -> node.name
        is ElixirAst.Literal.Binary -> String(node.bytes, Charsets.UTF_8)
        is ElixirAst.Literal, is ElixirAst.Value, is ElixirAst.ListNode, is ElixirAst.Tuple, is ElixirAst.Block,
        is ElixirAst.Placeholder,
        -> ""
    }

/** A sigil call's modifiers, the code points its last argument lists. */
private fun modifiers(sigil: ElixirAst.Call): String =
    (sigil.arguments?.lastOrNull() as? ElixirAst.ListNode)?.elements.orEmpty()
        .mapNotNull { (it as? ElixirAst.Literal.Integer)?.value?.toInt() }
        .joinToString("") { String(Character.toChars(it)) }

/** The scope at [root]'s `__cursor__`: the `alias` forms before it in each block that holds it. */
fun scope(root: ElixirAst): Scope {
    val aliases = mutableMapOf<String, String>()

    toCursor(root).zipWithNext { node, next ->
        if (node is ElixirAst.Block) {
            node.expressions.takeWhile { it !== next }.forEach { expression ->
                if (expression is ElixirAst.Call && (expression.callee as? ElixirAst.Literal.Atom)?.name == "alias") {
                    val arguments = expression.arguments.orEmpty()
                    val module = (arguments.firstOrNull() as? ElixirAst.Alias)?.let(::aliasName)

                    if (module != null) {
                        val `as` = (arguments.getOrNull(1) as? ElixirAst.ListNode)?.elements
                            ?.filterIsInstance<ElixirAst.Tuple>()
                            ?.firstOrNull { (it.elements.first() as? ElixirAst.Literal.Atom)?.name == "as" }
                            ?.let { it.elements[1] as? ElixirAst.Alias }
                            ?.let(::aliasName)

                        aliases[`as` ?: lastSegment(module)] = module
                    }
                }
            }
        }
    }

    return Scope(aliases)
}

private fun aliasName(alias: ElixirAst.Alias): String? =
    alias.segments.map { (it as? ElixirAst.Literal.Atom)?.name ?: return null }.joinToString(".")

/** `{name, meta, nil}`, which IEx reads as a variable. */
private fun isVariable(node: ElixirAst) =
    node is ElixirAst.Call && node.callee is ElixirAst.Literal.Atom && node.arguments == null && !isCursor(node)

private fun isCursor(node: ElixirAst) =
    node is ElixirAst.Placeholder || node is ElixirAst.Call && (node.callee as? ElixirAst.Literal.Atom)?.name == "__cursor__"

private fun head(node: ElixirAst): String? =
    when (node) {
        is ElixirAst.Call ->
            if (isCursor(node)) {
                CURSOR
            } else {
                val name = when (val callee = node.callee) {
                    is ElixirAst.Literal.Atom -> callee.name
                    is ElixirAst.Call -> dotted(callee)?.let { (_, member) -> ".${member ?: "()"}" } ?: "()"
                    else -> "()"
                }

                node.arguments?.let { "$name/${it.size}" } ?: name
            }
        is ElixirAst.Alias -> "alias"
        is ElixirAst.ListNode -> "[]"
        is ElixirAst.Tuple -> "{}/${node.elements.size}"
        is ElixirAst.Literal, is ElixirAst.Value -> "literal"
        is ElixirAst.Block -> null
        is ElixirAst.Placeholder -> CURSOR
    }

/** Which of [node]'s arguments, elements or segments [next] is; `null` for a call's receiver or callee. */
private fun slot(node: ElixirAst, next: ElixirAst): Int? =
    when (node) {
        is ElixirAst.Call -> node.arguments.orEmpty()
        is ElixirAst.Alias -> node.segments
        is ElixirAst.ListNode -> node.elements
        is ElixirAst.Tuple -> node.elements
        is ElixirAst.Block -> node.expressions
        is ElixirAst.Literal, is ElixirAst.Value, is ElixirAst.Placeholder -> emptyList()
    }.indexOfFirst { it === next }.takeIf { it >= 0 }

/**
 * Where [node]'s head is written: a call's callee, or a remote call's `.`, or an alias's first `.` where its first part
 * is not a name, as Elixir places `@mod.Sub` at its `.`, or where the node starts.
 */
private fun headStart(node: ElixirAst, text: String): Int =
    when (node) {
        is ElixirAst.Call -> ((node.callee as? ElixirAst.Call)?.takeIf { dotted(it) != null }?.callee ?: node.callee).meta.origin.startOffset
        is ElixirAst.Alias ->
            node.segments.first().takeUnless { it is ElixirAst.Literal.Atom }
                ?.let { dotAfter(text, it.meta.origin.endOffset) }
                ?: node.meta.origin.startOffset
        else -> node.meta.origin.startOffset
    }

/** [head] without its arity. */
private fun name(head: String) = head.replace(ARITY, "")

/** A `.` call's receiver and member, the member `null` for an anonymous call. */
private fun dotted(callee: ElixirAst.Call): Pair<ElixirAst, String?>? {
    if ((callee.callee as? ElixirAst.Literal.Atom)?.name != ".") return null
    val arguments = callee.arguments ?: return null
    val receiver = arguments.firstOrNull() ?: return null

    return when (arguments.size) {
        1 -> receiver to null
        2 -> (arguments[1] as? ElixirAst.Literal.Atom)?.let { receiver to it.name }
        else -> null
    }
}

private fun children(node: ElixirAst): List<ElixirAst> =
    when (node) {
        is ElixirAst.Call -> {
            val before = when (val callee = node.callee) {
                is ElixirAst.Literal.Atom -> emptyList()
                is ElixirAst.Call -> dotted(callee)?.let { listOf(it.first) } ?: listOf(callee)
                else -> listOf(callee)
            }

            before + node.arguments.orEmpty()
        }
        is ElixirAst.Alias -> node.segments.filter { it !is ElixirAst.Literal.Atom }
        is ElixirAst.ListNode -> node.elements
        is ElixirAst.Tuple -> node.elements
        is ElixirAst.Block -> node.expressions
        is ElixirAst.Literal, is ElixirAst.Value, is ElixirAst.Placeholder -> emptyList()
    }

private const val CURSOR = "cursor"
private val ARITY = Regex("/\\d+$")
private const val SHOWN = 3
private val ALIAS_ONLY = setOf("alias", "import", "require")
private val ALIAS_ONLY_HEADS = ALIAS_ONLY.map { "$it/1" }.toSet()
private val BITSTRING_MODIFIER_OPERATORS = setOf("::", "-")
