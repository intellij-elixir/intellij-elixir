package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.expander.DefinitionTable.Kind
import org.elixir_lang.language_level.ElixirLanguageFeature.COMPILER_PARSES_COLUMNS
import org.elixir_lang.language_level.ElixirLanguageFeature.FUNCTION_ERRORS_CONTINUE
import org.elixir_lang.language_level.ElixirLanguageFeature.LOCAL_CALL_CHECKED_BEFORE_ARGUMENTS
import org.elixir_lang.language_level.ElixirLanguageFeature.POST_MODULE_LOCAL_CHECKS_TYPED
import org.elixir_lang.language_level.ElixirLanguageFeature.GUARDS_INFER_TYPES
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.unicode_util.Graphemes

/**
 * A local call a function body made that wasn't dispatched to a macro, kept for the checks Elixir makes once the
 * module's body has run.
 *
 * @property at the call
 * @property line the call's line
 * @property column the call's column
 * @property arguments the local calls in its arguments, in expansion order
 * @property checked whether the call is checked: a default clause's call to its full arity isn't, as it calls the
 *   definition's own function, a macro's included
 */
internal data class LocalCall<out At>(
    val at: At,
    val called: NameArity,
    val line: Int,
    val column: Int,
    val arguments: List<LocalCall<At>> = emptyList(),
    val checked: Boolean = true,
)

/** A local call Elixir reports once the module's body has run, at [site], in [caller]'s env. */
internal data class LocalError<out At>(val site: ErrorSite, val call: LocalCall<At>, val caller: NameArity)

/**
 * The errors Elixir reports for a module's local calls once its body has run, in its order: `undefined_function` for
 * a call to a function the module doesn't define, and `incorrect_dispatch` for one to a macro it defines after the
 * call. Before [FUNCTION_ERRORS_CONTINUE] only the first is an error, and it raises.
 *
 * @param kinds each definition's kind, in the order they were defined
 * @param calls each definition's local calls, in expansion order
 * @param usedPrivate the private macros dispatched as local macros, in the order they were first dispatched
 */
internal fun <At> localErrors(
    level: ElixirLanguageLevel,
    kinds: Map<NameArity, Kind>,
    calls: Map<NameArity, List<LocalCall<At>>>,
    usedPrivate: List<NameArity>,
): List<LocalError<At>> =
    if (POST_MODULE_LOCAL_CHECKS_TYPED.isSufficient(level)) {
        Traversal(level, kinds, calls).errors(usedPrivate)
    } else {
        sorted(level, kinds, calls)
    }

/** The error for [call] from [caller], if it is one. */
private fun <At> errorOf(kinds: Map<NameArity, Kind>, call: LocalCall<At>, caller: NameArity): LocalError<At>? =
    when {
        !call.checked -> null
        call.called !in kinds -> LocalError(ErrorSite.UNDEFINED_FUNCTION, call, caller)
        kinds.getValue(call.called).macro -> LocalError(ErrorSite.INCORRECT_DISPATCH, call, caller)
        else -> null
    }

/**
 * `ensure_no_undefined_local`: every bad call, sorted, with equal keys once. Up to 1.14 a call's key is its line and
 * the called pair; from [FUNCTION_ERRORS_CONTINUE] the calling definition's pair comes first. From
 * [COMPILER_PARSES_COLUMNS] the line is the meta `calculate_span/2` builds, which leads with the line and the end of
 * the called name, then has the column.
 */
private fun <At> sorted(
    level: ElixirLanguageLevel,
    kinds: Map<NameArity, Kind>,
    calls: Map<NameArity, List<LocalCall<At>>>,
): List<LocalError<At>> {
    val byCaller = FUNCTION_ERRORS_CONTINUE.isSufficient(level)
    val columns = COMPILER_PARSES_COLUMNS.isSufficient(level)
    val graphemes = Graphemes.of(level)
    val key = { error: LocalError<At> ->
        val call = error.call
        val name = call.called.name
        val position =
            if (columns) listOf(call.line, call.column + graphemes.clusters(name).count(), call.column)
            else listOf(call.line)

        SortKey(error.caller.takeIf { byCaller }, position, call.called)
    }

    return calls
        .flatMap { (caller, callerCalls) ->
            callerCalls.flatMap { it.withArguments() }.mapNotNull { errorOf(kinds, it, caller) }
        }
        .sortedWith(compareBy(SortKey.ORDER, key))
        .distinctBy(key)
}

private fun <At> LocalCall<At>.withArguments(): List<LocalCall<At>> =
    listOf(this) + arguments.flatMap { it.withArguments() }

private data class SortKey(val caller: NameArity?, val position: List<Int>, val called: NameArity) {
    companion object {
        val ORDER: Comparator<SortKey> =
            compareBy<SortKey, NameArity?>(nullsFirst(NAME_ARITY_ORDER)) { it.caller }
                .thenComparing({ it.position }, { left, right ->
                    left.zip(right).map { (l, r) -> l.compareTo(r) }.firstOrNull { it != 0 } ?: 0
                })
                .thenComparing({ it.called }, NAME_ARITY_ORDER)
    }
}

/**
 * `Module.Types.infer`: each public definition is visited, then each private macro dispatched as a local macro, then
 * each private definition, descending. A call to a defined function visits it there, and each definition is visited
 * once, so every bad call is reported where the walk reaches it.
 */
private class Traversal<At>(
    level: ElixirLanguageLevel,
    private val kinds: Map<NameArity, Kind>,
    private val calls: Map<NameArity, List<LocalCall<At>>>,
) {
    private val callBeforeArguments = LOCAL_CALL_CHECKED_BEFORE_ARGUMENTS.isSufficient(level)
    private val publicAscending = GUARDS_INFER_TYPES.isSufficient(level)
    private val visited = mutableSetOf<NameArity>()
    private val errors = mutableListOf<LocalError<At>>()

    fun errors(usedPrivate: List<NameArity>): List<LocalError<At>> {
        val (public, private) = kinds.keys.sortedWith(NAME_ARITY_ORDER.reversed()).partition { kinds.getValue(it).public }

        (if (publicAscending) public.reversed() else public).forEach(::visit)
        usedPrivate.forEach(::visit)
        private.forEach(::visit)

        return errors
    }

    private fun visit(definition: NameArity) {
        if (visited.add(definition)) {
            calls[definition].orEmpty().forEach { walk(it, definition) }
        }
    }

    private fun walk(call: LocalCall<At>, caller: NameArity) {
        if (!callBeforeArguments) call.arguments.forEach { walk(it, caller) }

        when (val error = errorOf(kinds, call, caller)) {
            null -> visit(call.called)
            else -> errors += error
        }

        if (callBeforeArguments) call.arguments.forEach { walk(it, caller) }
    }
}
