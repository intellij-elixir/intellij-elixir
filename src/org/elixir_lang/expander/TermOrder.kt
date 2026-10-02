package org.elixir_lang.expander

import org.elixir_lang.NameArity

/** Erlang's term order over atom text: code point by code point, where `String` compares UTF-16 units. */
internal val ATOM_ORDER: Comparator<String> = Comparator { left, right ->
    val leftPoints = left.codePoints().iterator()
    val rightPoints = right.codePoints().iterator()

    while (leftPoints.hasNext() && rightPoints.hasNext()) {
        val compared = leftPoints.nextInt().compareTo(rightPoints.nextInt())

        if (compared != 0) return@Comparator compared
    }

    leftPoints.hasNext().compareTo(rightPoints.hasNext())
}

/**
 * Erlang's term order over a variable's context: an atom, a `{Module, n}` counter, or outside a module an integer
 * counter. A number sorts before an atom, which sorts before a tuple.
 */
private val CONTEXT_ORDER: Comparator<Variable.Context> =
    compareBy<Variable.Context> {
        when (it) {
            is Variable.Context.Atom -> 1
            is Variable.Context.Counter -> if (it.counter is Env.Counter.Unique) 0 else 2
        }
    }
        .thenBy(nullsFirst(ATOM_ORDER)) {
            when (it) {
                is Variable.Context.Atom -> it.text
                is Variable.Context.Counter -> (it.counter as? Env.Counter.InModule)?.module
            }
        }
        .thenBy {
            when (it) {
                is Variable.Context.Atom -> null
                is Variable.Context.Counter -> when (val counter = it.counter) {
                    is Env.Counter.InModule -> counter.n
                    is Env.Counter.Unique -> counter.n
                }
            }
        }

/** Erlang's term order over `{name, context}`, which a small map folds in. */
internal val VARIABLE_ORDER: Comparator<Variable> =
    compareBy(ATOM_ORDER, Variable::name).thenComparing(Variable::context, CONTEXT_ORDER)

/** Erlang's term order over `{name, arity}`. */
internal val NAME_ARITY_ORDER: Comparator<NameArity> =
    compareBy(ATOM_ORDER, NameArity::name).thenBy(NameArity::arity)
