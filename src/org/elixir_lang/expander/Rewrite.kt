package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageFeature
import org.elixir_lang.language_level.ElixirLanguageFeature.ATOM_TO_STRING_INLINED
import org.elixir_lang.language_level.ElixirLanguageFeature.IS_INTEGER_RANGE_GUARD
import org.elixir_lang.language_level.ElixirLanguageFeature.IS_RECORD_GUARD
import org.elixir_lang.language_level.ElixirLanguageFeature.MAP_FROM_KEYS_INLINED
import org.elixir_lang.language_level.ElixirLanguageFeature.MAP_INTERSECT_INLINED
import org.elixir_lang.language_level.ElixirLanguageFeature.MAX_AND_MIN_GUARDS
import org.elixir_lang.language_level.ElixirLanguageFeature.NODE_SPAWN_MONITOR_INLINED
import org.elixir_lang.language_level.ElixirLanguageFeature.PROCESS_ALIAS_INLINED
import org.elixir_lang.language_level.ElixirLanguageFeature.PUT_ELEM_IN_GUARD
import org.elixir_lang.language_level.ElixirLanguageFeature.STRING_TO_ATOM_INLINED
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.psi.Import.Term

/**
 * `elixir_rewrite:inline/3`: the Erlang function [module].[name]/[arity] compiles to at [level], as its module and
 * name, or `null` where it isn't inlined.
 */
internal fun inline(module: String, name: String, arity: Int, level: ElixirLanguageLevel): Pair<String, String>? =
    INLINED[Triple(module, name, arity)]
        ?.takeIf { (it.since?.isSufficient(level) ?: true) && it.removedBy?.isSufficient(level) != true }
        ?.let { it.erlangModule to it.erlangName }

/**
 * `elixir_rewrite:guard/6`, `guard_rewrite` before 1.18: whether a call of [receiver].[name]/[arity], after
 * [inline], is allowed in a guard at [level]. `Kernel.elem/2` and `Kernel.is_map_key/2` are rewritten to guard
 * functions, and `Kernel.put_elem/3` is allowed from [PUT_ELEM_IN_GUARD]; no other rewrite reaches one.
 */
internal fun isAllowedInGuard(receiver: String, name: String, arity: Int, level: ElixirLanguageLevel): Boolean =
    when (receiver) {
        KERNEL -> when (NameArity(name, arity)) {
            ELEM, IS_MAP_KEY -> true
            PUT_ELEM -> PUT_ELEM_IN_GUARD.isSufficient(level)
            else -> false
        }
        ERLANG -> isGuardFunction(NameArity(name, arity), level)
        else -> false
    }

/** `elixir_rewrite:allowed_guard/2` on the Erlang/OTP [level] runs on, which an unknown OTP takes as the newest. */
private fun isGuardFunction(nameArity: NameArity, level: ElixirLanguageLevel): Boolean =
    nameArity in OTP_24_GUARD_BIFS || GUARD_BIFS_SINCE[nameArity]?.isSufficient(level) == true

/** The guard functions Erlang/OTP added after 24. */
internal val GUARD_BIFS_SINCE = mapOf(
    NameArity("max", 2) to MAX_AND_MIN_GUARDS,
    NameArity("min", 2) to MAX_AND_MIN_GUARDS,
    NameArity("is_integer", 3) to IS_INTEGER_RANGE_GUARD,
    NameArity("is_record", 1) to IS_RECORD_GUARD,
)

/**
 * `elixir_rewrite:static_append/3`: [left] with [right] after its last element, or `null` where Elixir throws
 * `impossible`, as it does for anything but a proper list whose tails are written as lists.
 */
internal fun staticAppend(left: Term, right: Term): Term? {
    if (left !is Term.List) return null

    val tail = left.tail ?: return if (left.elements.isEmpty()) right else Term.List(left.elements, right)

    return staticAppend(tail, right)?.let { Term.List(left.elements, it) }
}

internal const val ERLANG = "erlang"
internal const val KERNEL = "Elixir.Kernel"

private val ELEM = NameArity("elem", 2)
private val IS_MAP_KEY = NameArity("is_map_key", 2)
private val PUT_ELEM = NameArity("put_elem", 3)

/**
 * A row of `inline/3`'s table.
 *
 * @property since the entry that adds the row
 * @property removedBy the entry that drops the row
 */
internal class Inlined(
    val module: String,
    val name: String,
    val arity: Int,
    val erlangModule: String,
    val erlangName: String,
    val since: ElixirLanguageFeature? = null,
    val removedBy: ElixirLanguageFeature? = null,
)

/** `inline/3`'s table: the `?inline` rows, and the hand-written rows for Bitwise's operators and `Port.demonitor`. */
internal val INLINED = listOf(
    Inlined("Elixir.Atom", "to_charlist", 1, "erlang", "atom_to_list"),
    Inlined("Elixir.Atom", "to_string", 1, "erlang", "atom_to_binary", since = ATOM_TO_STRING_INLINED),
    Inlined("Elixir.Bitwise", "&&&", 2, "erlang", "band"),
    Inlined("Elixir.Bitwise", "<<<", 2, "erlang", "bsl"),
    Inlined("Elixir.Bitwise", ">>>", 2, "erlang", "bsr"),
    Inlined("Elixir.Bitwise", "^^^", 2, "erlang", "bxor"),
    Inlined("Elixir.Bitwise", "band", 2, "erlang", "band"),
    Inlined("Elixir.Bitwise", "bnot", 1, "erlang", "bnot"),
    Inlined("Elixir.Bitwise", "bor", 2, "erlang", "bor"),
    Inlined("Elixir.Bitwise", "bsl", 2, "erlang", "bsl"),
    Inlined("Elixir.Bitwise", "bsr", 2, "erlang", "bsr"),
    Inlined("Elixir.Bitwise", "bxor", 2, "erlang", "bxor"),
    Inlined("Elixir.Bitwise", "|||", 2, "erlang", "bor"),
    Inlined("Elixir.Bitwise", "~~~", 1, "erlang", "bnot"),
    Inlined("Elixir.Function", "capture", 3, "erlang", "make_fun"),
    Inlined("Elixir.Function", "info", 1, "erlang", "fun_info"),
    Inlined("Elixir.Function", "info", 2, "erlang", "fun_info"),
    Inlined("Elixir.IO", "iodata_length", 1, "erlang", "iolist_size"),
    Inlined("Elixir.IO", "iodata_to_binary", 1, "erlang", "iolist_to_binary"),
    Inlined("Elixir.Integer", "to_charlist", 1, "erlang", "integer_to_list"),
    Inlined("Elixir.Integer", "to_charlist", 2, "erlang", "integer_to_list"),
    Inlined("Elixir.Integer", "to_string", 1, "erlang", "integer_to_binary"),
    Inlined("Elixir.Integer", "to_string", 2, "erlang", "integer_to_binary"),
    Inlined("Elixir.Kernel", "!=", 2, "erlang", "/="),
    Inlined("Elixir.Kernel", "!==", 2, "erlang", "=/="),
    Inlined("Elixir.Kernel", "*", 2, "erlang", "*"),
    Inlined("Elixir.Kernel", "+", 1, "erlang", "+"),
    Inlined("Elixir.Kernel", "+", 2, "erlang", "+"),
    Inlined("Elixir.Kernel", "++", 2, "erlang", "++"),
    Inlined("Elixir.Kernel", "-", 1, "erlang", "-"),
    Inlined("Elixir.Kernel", "-", 2, "erlang", "-"),
    Inlined("Elixir.Kernel", "--", 2, "erlang", "--"),
    Inlined("Elixir.Kernel", "/", 2, "erlang", "/"),
    Inlined("Elixir.Kernel", "<", 2, "erlang", "<"),
    Inlined("Elixir.Kernel", "<=", 2, "erlang", "=<"),
    Inlined("Elixir.Kernel", "==", 2, "erlang", "=="),
    Inlined("Elixir.Kernel", "===", 2, "erlang", "=:="),
    Inlined("Elixir.Kernel", ">", 2, "erlang", ">"),
    Inlined("Elixir.Kernel", ">=", 2, "erlang", ">="),
    Inlined("Elixir.Kernel", "abs", 1, "erlang", "abs"),
    Inlined("Elixir.Kernel", "apply", 2, "erlang", "apply"),
    Inlined("Elixir.Kernel", "apply", 3, "erlang", "apply"),
    Inlined("Elixir.Kernel", "binary_part", 3, "erlang", "binary_part"),
    Inlined("Elixir.Kernel", "bit_size", 1, "erlang", "bit_size"),
    Inlined("Elixir.Kernel", "byte_size", 1, "erlang", "byte_size"),
    Inlined("Elixir.Kernel", "ceil", 1, "erlang", "ceil"),
    Inlined("Elixir.Kernel", "div", 2, "erlang", "div"),
    Inlined("Elixir.Kernel", "exit", 1, "erlang", "exit"),
    Inlined("Elixir.Kernel", "floor", 1, "erlang", "floor"),
    Inlined("Elixir.Kernel", "function_exported?", 3, "erlang", "function_exported"),
    Inlined("Elixir.Kernel", "hd", 1, "erlang", "hd"),
    Inlined("Elixir.Kernel", "is_atom", 1, "erlang", "is_atom"),
    Inlined("Elixir.Kernel", "is_binary", 1, "erlang", "is_binary"),
    Inlined("Elixir.Kernel", "is_bitstring", 1, "erlang", "is_bitstring"),
    Inlined("Elixir.Kernel", "is_boolean", 1, "erlang", "is_boolean"),
    Inlined("Elixir.Kernel", "is_float", 1, "erlang", "is_float"),
    Inlined("Elixir.Kernel", "is_function", 1, "erlang", "is_function"),
    Inlined("Elixir.Kernel", "is_function", 2, "erlang", "is_function"),
    Inlined("Elixir.Kernel", "is_integer", 1, "erlang", "is_integer"),
    Inlined("Elixir.Kernel", "is_list", 1, "erlang", "is_list"),
    Inlined("Elixir.Kernel", "is_map", 1, "erlang", "is_map"),
    Inlined("Elixir.Kernel", "is_number", 1, "erlang", "is_number"),
    Inlined("Elixir.Kernel", "is_pid", 1, "erlang", "is_pid"),
    Inlined("Elixir.Kernel", "is_port", 1, "erlang", "is_port"),
    Inlined("Elixir.Kernel", "is_reference", 1, "erlang", "is_reference"),
    Inlined("Elixir.Kernel", "is_tuple", 1, "erlang", "is_tuple"),
    Inlined("Elixir.Kernel", "length", 1, "erlang", "length"),
    Inlined("Elixir.Kernel", "make_ref", 0, "erlang", "make_ref"),
    Inlined("Elixir.Kernel", "map_size", 1, "erlang", "map_size"),
    Inlined("Elixir.Kernel", "max", 2, "erlang", "max"),
    Inlined("Elixir.Kernel", "min", 2, "erlang", "min"),
    Inlined("Elixir.Kernel", "node", 0, "erlang", "node"),
    Inlined("Elixir.Kernel", "node", 1, "erlang", "node"),
    Inlined("Elixir.Kernel", "not", 1, "erlang", "not"),
    Inlined("Elixir.Kernel", "rem", 2, "erlang", "rem"),
    Inlined("Elixir.Kernel", "round", 1, "erlang", "round"),
    Inlined("Elixir.Kernel", "self", 0, "erlang", "self"),
    Inlined("Elixir.Kernel", "send", 2, "erlang", "send"),
    Inlined("Elixir.Kernel", "spawn", 1, "erlang", "spawn"),
    Inlined("Elixir.Kernel", "spawn", 3, "erlang", "spawn"),
    Inlined("Elixir.Kernel", "spawn_link", 1, "erlang", "spawn_link"),
    Inlined("Elixir.Kernel", "spawn_link", 3, "erlang", "spawn_link"),
    Inlined("Elixir.Kernel", "spawn_monitor", 1, "erlang", "spawn_monitor"),
    Inlined("Elixir.Kernel", "spawn_monitor", 3, "erlang", "spawn_monitor"),
    Inlined("Elixir.Kernel", "throw", 1, "erlang", "throw"),
    Inlined("Elixir.Kernel", "tl", 1, "erlang", "tl"),
    Inlined("Elixir.Kernel", "trunc", 1, "erlang", "trunc"),
    Inlined("Elixir.Kernel", "tuple_size", 1, "erlang", "tuple_size"),
    Inlined("Elixir.List", "to_atom", 1, "erlang", "list_to_atom"),
    Inlined("Elixir.List", "to_existing_atom", 1, "erlang", "list_to_existing_atom"),
    Inlined("Elixir.List", "to_float", 1, "erlang", "list_to_float"),
    Inlined("Elixir.List", "to_integer", 1, "erlang", "list_to_integer"),
    Inlined("Elixir.List", "to_integer", 2, "erlang", "list_to_integer"),
    Inlined("Elixir.List", "to_tuple", 1, "erlang", "list_to_tuple"),
    Inlined("Elixir.Map", "from_keys", 2, "maps", "from_keys", since = MAP_FROM_KEYS_INLINED),
    Inlined("Elixir.Map", "intersect", 2, "maps", "intersect", since = MAP_INTERSECT_INLINED),
    Inlined("Elixir.Map", "keys", 1, "maps", "keys"),
    Inlined("Elixir.Map", "merge", 2, "maps", "merge"),
    Inlined("Elixir.Map", "to_list", 1, "maps", "to_list"),
    Inlined("Elixir.Map", "values", 1, "maps", "values"),
    Inlined("Elixir.Node", "list", 0, "erlang", "nodes"),
    Inlined("Elixir.Node", "list", 1, "erlang", "nodes"),
    Inlined("Elixir.Node", "spawn", 2, "erlang", "spawn"),
    Inlined("Elixir.Node", "spawn", 3, "erlang", "spawn_opt"),
    Inlined("Elixir.Node", "spawn", 4, "erlang", "spawn"),
    Inlined("Elixir.Node", "spawn", 5, "erlang", "spawn_opt"),
    Inlined("Elixir.Node", "spawn_link", 2, "erlang", "spawn_link"),
    Inlined("Elixir.Node", "spawn_link", 4, "erlang", "spawn_link"),
    Inlined("Elixir.Node", "spawn_monitor", 2, "erlang", "spawn_monitor", since = NODE_SPAWN_MONITOR_INLINED),
    Inlined("Elixir.Node", "spawn_monitor", 4, "erlang", "spawn_monitor", since = NODE_SPAWN_MONITOR_INLINED),
    Inlined("Elixir.Port", "close", 1, "erlang", "port_close"),
    Inlined("Elixir.Port", "command", 2, "erlang", "port_command"),
    Inlined("Elixir.Port", "command", 3, "erlang", "port_command"),
    Inlined("Elixir.Port", "connect", 2, "erlang", "port_connect"),
    Inlined("Elixir.Port", "demonitor", 1, "erlang", "demonitor"),
    Inlined("Elixir.Port", "demonitor", 2, "erlang", "demonitor"),
    Inlined("Elixir.Port", "list", 0, "erlang", "ports"),
    Inlined("Elixir.Port", "open", 2, "erlang", "open_port"),
    Inlined("Elixir.Process", "alias", 0, "erlang", "alias", since = PROCESS_ALIAS_INLINED),
    Inlined("Elixir.Process", "alias", 1, "erlang", "alias", since = PROCESS_ALIAS_INLINED),
    Inlined("Elixir.Process", "alive?", 1, "erlang", "is_process_alive"),
    Inlined("Elixir.Process", "cancel_timer", 1, "erlang", "cancel_timer"),
    Inlined("Elixir.Process", "cancel_timer", 2, "erlang", "cancel_timer"),
    Inlined("Elixir.Process", "demonitor", 1, "erlang", "demonitor"),
    Inlined("Elixir.Process", "demonitor", 2, "erlang", "demonitor"),
    Inlined("Elixir.Process", "exit", 2, "erlang", "exit"),
    Inlined("Elixir.Process", "flag", 2, "erlang", "process_flag"),
    Inlined("Elixir.Process", "flag", 3, "erlang", "process_flag"),
    Inlined("Elixir.Process", "get", 0, "erlang", "get"),
    Inlined("Elixir.Process", "get_keys", 0, "erlang", "get_keys"),
    Inlined("Elixir.Process", "get_keys", 1, "erlang", "get_keys"),
    Inlined("Elixir.Process", "group_leader", 0, "erlang", "group_leader"),
    Inlined("Elixir.Process", "hibernate", 3, "erlang", "hibernate"),
    Inlined("Elixir.Process", "link", 1, "erlang", "link"),
    Inlined("Elixir.Process", "list", 0, "erlang", "processes"),
    Inlined("Elixir.Process", "read_timer", 1, "erlang", "read_timer"),
    Inlined("Elixir.Process", "registered", 0, "erlang", "registered"),
    Inlined("Elixir.Process", "send", 3, "erlang", "send"),
    Inlined("Elixir.Process", "spawn", 2, "erlang", "spawn_opt"),
    Inlined("Elixir.Process", "spawn", 4, "erlang", "spawn_opt"),
    Inlined("Elixir.Process", "unalias", 1, "erlang", "unalias", since = PROCESS_ALIAS_INLINED),
    Inlined("Elixir.Process", "unlink", 1, "erlang", "unlink"),
    Inlined("Elixir.Process", "unregister", 1, "erlang", "unregister"),
    Inlined("Elixir.String", "duplicate", 2, "binary", "copy"),
    Inlined("Elixir.String", "to_atom", 1, "erlang", "binary_to_atom", since = STRING_TO_ATOM_INLINED),
    Inlined(
        "Elixir.String",
        "to_existing_atom",
        1,
        "erlang",
        "binary_to_existing_atom",
        since = STRING_TO_ATOM_INLINED,
    ),
    Inlined("Elixir.String", "to_float", 1, "erlang", "binary_to_float"),
    Inlined("Elixir.String", "to_integer", 1, "erlang", "binary_to_integer"),
    Inlined("Elixir.String", "to_integer", 2, "erlang", "binary_to_integer"),
    Inlined("Elixir.System", "monotonic_time", 0, "erlang", "monotonic_time"),
    Inlined("Elixir.System", "os_time", 0, "os", "system_time"),
    Inlined("Elixir.System", "system_time", 0, "erlang", "system_time"),
    Inlined("Elixir.System", "time_offset", 0, "erlang", "time_offset"),
    Inlined("Elixir.System", "unique_integer", 0, "erlang", "unique_integer"),
    Inlined("Elixir.System", "unique_integer", 1, "erlang", "unique_integer"),
    Inlined("Elixir.Tuple", "append", 2, "erlang", "append_element", removedBy = STRING_TO_ATOM_INLINED),
    Inlined("Elixir.Tuple", "to_list", 1, "erlang", "tuple_to_list"),
).associateBy { Triple(it.module, it.name, it.arity) }
