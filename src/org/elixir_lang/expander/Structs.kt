package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangMap
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple

/**
 * Each module's struct, as `defstruct` records it in the module's metadata. A module the expanded file defines is
 * answered from the definitions expanded so far, never from compiled metadata.
 */
fun interface Structs {
    /** [module]'s struct; [module] is atom text, as for [Exports]. */
    fun of(module: String): ModuleStruct
}

sealed class ModuleStruct {
    /** No `__struct__/1`: no such module, or a module that defines no struct. */
    data object Absent : ModuleStruct()

    /** It exports `__struct__/1`, but its struct metadata can't be read. */
    data object Unreadable : ModuleStruct()

    /** @property fields the field names, as atom text */
    data class Present(val fields: Set<String>, val enforced: Enforced) : ModuleStruct()

    companion object {
        /**
         * The struct the `:struct` value of an `elixir_v1` `Dbgi` map records, read by its shape, which is the
         * compiling Elixir's: `{default_struct, enforced_keys}` up to 1.13, then a list of `%{field, default,
         * required}`, without `required` on 1.18 and 1.19.0-rc.0.
         */
        fun from(term: OtpErlangObject): ModuleStruct =
            when (term) {
                is OtpErlangTuple -> fromDefaultStruct(term)
                is OtpErlangList -> fromFieldList(term)
                else -> null
            } ?: Unreadable

        private fun fromDefaultStruct(term: OtpErlangTuple): ModuleStruct? {
            if (term.arity() != 2) return null
            val default = term.elementAt(0) as? OtpErlangMap ?: return null
            val fields = default.keys().map { (it as? OtpErlangAtom)?.atomValue() ?: return null }.toSet()
            val enforced = atomNames(term.elementAt(1)) ?: return null

            return Present(fields - "__struct__", Enforced.Known(enforced.toSet()))
        }

        private fun fromFieldList(term: OtpErlangList): ModuleStruct? {
            if (term.lastTail != null) return null
            val fields = term.elements().map { it as? OtpErlangMap ?: return null }
            val names = fields.map { (it.get(FIELD) as? OtpErlangAtom)?.atomValue() ?: return null }
            val required = fields.map { it.get(REQUIRED) }
            val enforced = when {
                fields.isNotEmpty() && required.all { it == null } -> Enforced.Unknown
                required.all { it == TRUE || it == FALSE } ->
                    Enforced.Known(names.filterIndexed { index, _ -> required[index] == TRUE }.toSet())
                else -> return null
            }

            return Present(names.toSet(), enforced)
        }

        private fun atomNames(term: OtpErlangObject): List<String>? =
            (term as? OtpErlangList)
                ?.takeIf { it.lastTail == null }
                ?.elements()
                ?.map { (it as? OtpErlangAtom)?.atomValue() ?: return null }

        private val FIELD = OtpErlangAtom("field")
        private val REQUIRED = OtpErlangAtom("required")
        private val TRUE = OtpErlangAtom("true")
        private val FALSE = OtpErlangAtom("false")
    }
}

sealed class Enforced {
    data class Known(val keys: Set<String>) : Enforced()

    /** The metadata records no enforced keys either way, as 1.18's and 1.19.0-rc.0's do. */
    data object Unknown : Enforced()
}
