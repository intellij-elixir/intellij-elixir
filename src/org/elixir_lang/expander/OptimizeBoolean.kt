package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageFeature.COMPILER_PARSES_COLUMNS
import org.elixir_lang.language_level.ElixirLanguageFeature.FALSE_OR_NIL_INLINE
import org.elixir_lang.language_level.ElixirLanguageFeature.RETURNS_BOOLEAN_LISTS_MEMBER
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.ParserOptions
import org.elixir_lang.psi.Import.Term

/** `elixir_utils:returns_boolean/1` of the expansion whose value is [value]. */
internal fun returnsBoolean(value: Term): Boolean = value == TRUE || value == FALSE || value == BOOLEAN_NODE

/** The value of a call of [module].[name] with [args], after `inline/3`, outside a pattern or a guard. */
internal fun callValue(module: String?, name: String, args: List<Term>, level: ElixirLanguageLevel): Term {
    if (module == null) return NODE

    val rewritten = rewrite(module, name, args.size, level)
    val returnsBoolean = if (rewritten == null) {
        returnsBoolean(module, name, args, level)
    } else {
        // No rewrite gives `andalso` or `orelse`, the only calls whose value reads their arguments.
        returnsBoolean(rewritten.erlangModule, rewritten.erlangName, List(rewritten.erlangArity) { NODE }, level)
    }

    return if (returnsBoolean) BOOLEAN_NODE else NODE
}

/** The value of a `case` or `cond` whose clauses' bodies have [values]. */
internal fun clausesValue(values: List<Term>): Term = if (values.all(::returnsBoolean)) BOOLEAN_NODE else NODE

private fun returnsBoolean(module: String, name: String, args: List<Term>, level: ElixirLanguageLevel): Boolean =
    when (module) {
        "erlang" -> when (args.size) {
            1 -> name == "not" || name in GUARDS_1
            2 -> when (name) {
                in OPERATORS, "is_function", "is_map_key", "is_record" -> true
                "andalso", "orelse" -> returnsBoolean(args[1])
                else -> false
            }
            3 -> name == "function_exported" || name == "is_record"
            else -> false
        }
        "lists" -> name == "member" && args.size == 2 && RETURNS_BOOLEAN_LISTS_MEMBER.isSufficient(level)
        else -> false
    }

private val OPERATORS = setOf("and", "or", "xor", "==", "/=", "=<", ">=", "<", ">", "=:=", "=/=")

private val GUARDS_1 = setOf(
    "is_atom", "is_binary", "is_bitstring", "is_boolean", "is_float", "is_function", "is_integer", "is_list",
    "is_number", "is_pid", "is_port", "is_reference", "is_tuple", "is_map", "is_process_alive",
)

/**
 * `elixir_expand:rewrite_case_clauses/1` of a `case`'s [options] at [level]: `if`'s two clauses, or a `false` and a
 * `true` clause before any others, as those two alone; [options] itself where neither is there.
 */
internal fun rewriteCaseClauses(options: ElixirAst, level: ElixirLanguageLevel): ElixirAst {
    val clauses = (options as? ElixirAst.ListNode)
        ?.elements
        ?.singleOrNull()
        ?.takeIf { keyOf(it) == "do" }
        ?.let { ((it as ElixirAst.Tuple).elements[1] as? ElixirAst.ListNode)?.elements }
        ?: return options
    val (falseArrow, trueArrow) = clauses.take(2)
        .mapNotNull { clause -> (clause as? ElixirAst.Call)?.takeIf { isCall(it, "->", 2) } }
        .takeIf { it.size == 2 }
        ?: return options
    val falsePattern = patternOf(falseArrow)
    val truePattern = patternOf(trueArrow)
    val isIf = clauses.size == 2 && isFalseOrNilGuard(falsePattern, level) &&
        (truePattern as? ElixirAst.Call)?.let(::variableName) == "_"
    val isBooleans = (falsePattern as? ElixirAst.Literal.Atom)?.name == "false" &&
        (truePattern as? ElixirAst.Literal.Atom)?.name == "true"

    if (!isIf && !isBooleans) return options

    fun arrow(arrow: ElixirAst.Call, atom: String): ElixirAst.Call {
        val s = Synthetic(arrow.meta)

        return ElixirAst.Call(arrow.meta, arrow.callee, listOf(s.list(s.atom(atom)), arrow.arguments!![1]))
    }

    val s = Synthetic(options.meta)

    return s.list(s.tuple(s.atom("do"), s.list(arrow(falseArrow, "false"), arrow(trueArrow, "true"))))
}

/** The only pattern of [arrow], or `null` where it has more or fewer. */
private fun patternOf(arrow: ElixirAst.Call): ElixirAst? =
    (arrow.arguments!![0] as? ElixirAst.ListNode)?.elements?.singleOrNull()

/**
 * Whether [pattern] is `if`'s `x when ...` at [level]: from [FALSE_OR_NIL_INLINE], a `Kernel` variable compared to
 * `false` and `nil` by name; before it, `Kernel.in(x, [false, nil])` of the same variable term.
 */
private fun isFalseOrNilGuard(pattern: ElixirAst?, level: ElixirLanguageLevel): Boolean {
    val (variable, guard) = pattern?.let(::whenArguments)?.takeIf { it.size == 2 } ?: return false

    return if (FALSE_OR_NIL_INLINE.isSufficient(level)) {
        val name = kernelVariableName(variable) ?: return false
        val (isFalse, isNil) = remoteArguments(guard, "erlang", "orelse")?.takeIf { it.size == 2 } ?: return false

        fun isCompared(comparison: ElixirAst, atom: String): Boolean {
            val (left, right) = remoteArguments(comparison, "erlang", "=:=")?.takeIf { it.size == 2 } ?: return false

            return kernelVariableName(left) == name && (right as? ElixirAst.Literal.Atom)?.name == atom
        }

        isCompared(isFalse, "false") && isCompared(isNil, "nil")
    } else {
        val (left, list) = remoteArguments(guard, "Elixir.Kernel", "in")?.takeIf { it.size == 2 } ?: return false
        val atoms = (list as? ElixirAst.ListNode)?.elements?.map { (it as? ElixirAst.Literal.Atom)?.name }
        val options = ParserOptions(columns = COMPILER_PARSES_COLUMNS.isSufficient(level))

        atoms == listOf("false", "nil") && left.toOtp(options) == variable.toOtp(options)
    }
}

/** The name of [node], a variable of `Kernel`'s context. */
private fun kernelVariableName(node: ElixirAst): String? =
    (node as? ElixirAst.Call)
        ?.takeIf { it.arguments == null && it.context == ElixirAst.VariableContext.Atom("Elixir.Kernel") }
        ?.let(::variableName)

private fun variableName(node: ElixirAst.Call): String? = (node.callee as? ElixirAst.Literal.Atom)?.name

/** The arguments of [node], a call of [module].[function] written with an atom module, or `null` where it isn't one. */
private fun remoteArguments(node: ElixirAst, module: String, function: String): List<ElixirAst>? {
    val call = node as? ElixirAst.Call ?: return null
    val (receiver, name) = (call.callee as? ElixirAst.Call)?.takeIf { isCall(it, ".", 2) }?.arguments ?: return null

    return call.arguments?.takeIf {
        (receiver as? ElixirAst.Literal.Atom)?.name == module && (name as? ElixirAst.Literal.Atom)?.name == function
    }
}
