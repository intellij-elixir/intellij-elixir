package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangMap
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import org.elixir_lang.Macro
import org.elixir_lang.NameArity

/**
 * Each module's struct, as `defstruct` records it in the module's metadata. A module the expanded file defines is
 * answered from the definitions expanded so far, never from compiled metadata.
 */
fun interface Structs {
    /** [module]'s struct; [module] is atom text, as for [Exports]. */
    fun of(module: String): ModuleStruct
}

/**
 * Whether [nameArity] is what builds a module's struct: `__struct__/1`, and `__struct__/0` which a struct in a pattern
 * or an update is read through before 1.18. Where a module defines it other than as `defstruct` does, the struct
 * can't be read.
 */
internal fun buildsStruct(nameArity: NameArity): Boolean = nameArity.name == "__struct__" && nameArity.arity <= 1

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
         * required}`, without `required` on 1.18 and 1.19.0-rc.0, where the enforced keys are read from `__struct__/1`
         * in the map's [definitions].
         */
        fun from(term: OtpErlangObject, definitions: OtpErlangObject? = null): ModuleStruct =
            when (term) {
                is OtpErlangTuple -> fromDefaultStruct(term)
                is OtpErlangList -> fromFieldList(term, definitions)
                else -> null
            } ?: Unreadable

        private fun fromDefaultStruct(term: OtpErlangTuple): ModuleStruct? {
            if (term.arity() != 2) return null
            val default = term.elementAt(0) as? OtpErlangMap ?: return null
            val fields = default.keys().map { (it as? OtpErlangAtom)?.atomValue() ?: return null }.toSet()
            val enforced = atomNames(term.elementAt(1)) ?: return null

            return Present(fields - "__struct__", Enforced.Known(enforced.toSet()))
        }

        private fun fromFieldList(term: OtpErlangList, definitions: OtpErlangObject?): ModuleStruct? {
            if (term.lastTail != null) return null
            val fields = term.elements().map { it as? OtpErlangMap ?: return null }
            val names = fields.map { (it.get(FIELD) as? OtpErlangAtom)?.atomValue() ?: return null }
            val required = fields.map { it.get(REQUIRED) }
            val enforced = when {
                fields.isNotEmpty() && required.all { it == null } -> enforcedIn(definitions)
                required.all { it == TRUE || it == FALSE } ->
                    Enforced.Known(names.filterIndexed { index, _ -> required[index] == TRUE }.toSet())
                else -> return null
            }

            return Present(names.toSet(), enforced)
        }

        /**
         * The keys `defstruct` enforces, from the one clause it gives `__struct__/1` in [definitions]: `Enum.reduce(kv,
         * struct, fun)` with none, or `{map, keys} = Enum.reduce(kv, {struct, enforced_keys}, fun)` followed by the
         * check of what is left (`Kernel.Utils.defstruct/3`).
         */
        private fun enforcedIn(definitions: OtpErlangObject?): Enforced {
            val clauses = (definitions as? OtpErlangList)
                ?.elements()
                ?.filterIsInstance<OtpErlangTuple>()
                ?.singleOrNull { it.arity() == 4 && it.elementAt(0) == STRUCT_1 }
                ?.elementAt(3) as? OtpErlangList
            val clause = clauses?.elements()?.singleOrNull() as? OtpErlangTuple
            val body = clause?.takeIf { it.arity() == 4 }?.elementAt(3)

            return body?.let(::reduced) ?: Enforced.Unknown
        }

        private fun reduced(body: OtpErlangObject): Enforced? {
            call(body, BLOCK)?.let { statements ->
                val (first, check) = statements.takeIf { it.size == 2 } ?: return null
                val match = call(first, MATCH)?.takeIf { it.size == 2 } ?: return null
                val left = (match[0] as? OtpErlangTuple)?.takeIf { it.arity() == 2 } ?: return null
                if (!isVariable(call(check, CASE)?.firstOrNull() ?: return null, left.elementAt(1))) return null
                val acc = reduceAcc(match[1]) as? OtpErlangTuple ?: return null
                if (acc.arity() != 2 || !isMap(acc.elementAt(0))) return null

                return atomNames(acc.elementAt(1))?.let { Enforced.Known(it.toSet()) }
            }

            return reduceAcc(body)?.takeIf(::isMap)?.let { Enforced.Known(emptySet()) }
        }

        /** The accumulator of `Enum.reduce(kv, acc, fun)`. */
        private fun reduceAcc(node: OtpErlangObject): OtpErlangObject? {
            val call = node as? OtpErlangTuple ?: return null
            if (call.arity() != 3) return null
            val dot = call(call.elementAt(0), DOT) ?: return null
            if (dot != listOf(ENUM, REDUCE)) return null

            return (call.elementAt(2) as? OtpErlangList)?.elements()?.takeIf { it.size == 3 }?.get(1)
        }

        /** The arguments of [node] when it is a call to [name], `{name, meta, args}`. */
        private fun call(node: OtpErlangObject, name: OtpErlangAtom): List<OtpErlangObject>? =
            (node as? OtpErlangTuple)
                ?.takeIf { it.arity() == 3 && it.elementAt(0) == name }
                ?.let { it.elementAt(2) as? OtpErlangList }
                ?.takeIf { it.lastTail == null }
                ?.elements()
                ?.toList()

        private fun isMap(node: OtpErlangObject): Boolean = call(node, MAP) != null

        /** Whether [node] is the variable [variable] is, `{name, meta, context}`, whatever the meta of each. */
        private fun isVariable(node: OtpErlangObject, variable: OtpErlangObject): Boolean =
            Macro.isVariable(node) && Macro.isVariable(variable) && listOf(0, 2).all { index ->
                (node as OtpErlangTuple).elementAt(index) == (variable as OtpErlangTuple).elementAt(index)
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
        private val STRUCT_1 = OtpErlangTuple(arrayOf(OtpErlangAtom("__struct__"), OtpErlangLong(1)))
        private val BLOCK = OtpErlangAtom("__block__")
        private val MATCH = OtpErlangAtom("=")
        private val CASE = OtpErlangAtom("case")
        private val DOT = OtpErlangAtom(".")
        private val MAP = OtpErlangAtom("%{}")
        private val ENUM = OtpErlangAtom("Elixir.Enum")
        private val REDUCE = OtpErlangAtom("reduce")
    }
}

sealed class Enforced {
    data class Known(val keys: Set<String>) : Enforced()

    /** The metadata records no enforced keys either way, and `__struct__/1` isn't of a shape `defstruct` gives it. */
    data object Unknown : Enforced()
}
