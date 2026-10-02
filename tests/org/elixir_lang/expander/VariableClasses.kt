package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangMap
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple

/**
 * Variables as equivalence classes: two variables are in one class when they are at the same version. Classes, unlike
 * versions, don't depend on the order versions are allocated in, which differs between Elixir releases.
 */
object VariableClasses {
    /**
     * The variables a probe's `__CALLER__` holds: `versioned_vars` from 1.13, and before it the read half of
     * `current_vars`.
     */
    fun observed(env: OtpErlangMap): Map<Variable, Int> {
        val vars = env.get(OtpErlangAtom("versioned_vars"))
            ?: (env.get(OtpErlangAtom("current_vars")) as OtpErlangTuple).elementAt(0)

        return (vars as OtpErlangMap).entrySet().associate { (key, version) ->
            val (name, context) = (key as OtpErlangTuple).elements()

            Variable((name as OtpErlangAtom).atomValue(), context(context)) to (version as OtpErlangLong).intValue()
        }
    }

    /** A variable's context as `var_context` leaves it: an atom, `{Module, n}`, or the integer taken outside a module. */
    fun context(term: OtpErlangObject): Variable.Context =
        when (term) {
            is OtpErlangAtom -> Variable.Context.Atom(term.atomValue())
            is OtpErlangTuple -> {
                val (module, n) = term.elements()

                Variable.Context.Counter(
                    Env.Counter.InModule((module as OtpErlangAtom).atomValue(), (n as OtpErlangLong).longValue())
                )
            }
            is OtpErlangLong -> Variable.Context.Counter(Env.Counter.Unique(term.longValue()))
            else -> throw AssertionError("not a variable context: $term")
        }

    /**
     * One line per step: each variable, in Erlang's term order, with its class, numbered in the order the classes first
     * appear across [steps]. A counter context is `cN`, numbered the same way.
     */
    fun canonical(steps: List<Map<Variable, Int>>): List<String> {
        val classes = mutableMapOf<Int, Int>()
        val counters = mutableMapOf<Env.Counter, Int>()

        return steps.map { step ->
            step.entries
                .sortedWith(compareBy(VARIABLE_ORDER) { it.key })
                .joinToString(" ") { (variable, version) ->
                    val context = when (val context = variable.context) {
                        is Variable.Context.Atom -> context.text
                        is Variable.Context.Counter -> "c${counters.getOrPut(context.counter) { counters.size }}"
                    }

                    "${variable.name}/$context=${classes.getOrPut(version) { classes.size }}"
                }
        }
    }
}
