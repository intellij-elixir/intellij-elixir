package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.expander.DefinitionTable.Kind
import org.elixir_lang.expander.DefinitionTable.Kind.DEF
import org.elixir_lang.expander.DefinitionTable.Kind.DEFMACRO
import org.elixir_lang.expander.DefinitionTable.Kind.DEFMACROP
import org.elixir_lang.expander.DefinitionTable.Kind.DEFP
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [localErrors] over modules built by hand, each the shape of a module compiled in plain Elixir on 1.14.5, 1.15.8,
 * 1.16.3, 1.17.3, 1.18.4, 1.19.5 and 1.20.4, with the errors each reported in its order. Lines and columns are the
 * calls' in those modules. Up to 1.14 only the first is an error.
 */
class LocalChecksTest {
    @Test
    fun `definitions that call each other`() =
        assertOrders(
            module {
                def("a", call("x1", 3, 5), call("b", 4, 5), call("x2", 5, 5))
                def("b", call("y", 7, 14))
                def("d", call("z", 8, 14))
            }
        ) { version ->
            val x1 = undefined("x1", "a/0")
            val x2 = undefined("x2", "a/0")
            val y = undefined("y", "b/0")
            val z = undefined("z", "d/0")

            when {
                isBefore(version, "1.18.0-rc.0") -> listOf(x1, x2, y, z)
                isBefore(version, "1.20.0-rc.0") -> listOf(z, y, x1, x2)
                else -> listOf(x1, y, x2, z)
            }
        }

    @Test
    fun `a private function called from a public one`() =
        assertOrders(
            module {
                def("z1", call("p", 3, 5), call("q1", 4, 5))
                def("p", call("q2", 6, 15), kind = DEFP)
                def("a1", call("q3", 7, 15))
            }
        ) { version ->
            val q1 = undefined("q1", "z1/0")
            val q2 = undefined("q2", "p/0")
            val q3 = undefined("q3", "a1/0")

            when {
                isBefore(version, "1.15.0-rc.0") -> listOf(q1, q2, q3)
                isBefore(version, "1.18.0-rc.0") -> listOf(q3, q2, q1)
                isBefore(version, "1.20.0-rc.0") -> listOf(q2, q1, q3)
                else -> listOf(q3, q2, q1)
            }
        }

    @Test
    fun `an unused private function defined first`() =
        assertOrders(
            module {
                def("c", call("w", 2, 15), kind = DEFP)
                def("b", call("v", 3, 14))
            }
        ) { version ->
            val w = undefined("w", "c/0")
            val v = undefined("v", "b/0")

            if (isBefore(version, "1.15.0-rc.0")) listOf(w, v) else listOf(v, w)
        }

    @Test
    fun `a function called from two others, and two unused private functions`() =
        assertOrders(
            module {
                def("pa", call("u1", 2, 16), kind = DEFP)
                def("pb", call("u2", 3, 16), kind = DEFP)
                def("m", call("k", 5, 5), call("u3", 6, 5))
                def("k", call("u4", 8, 14))
                def("n", call("k", 10, 5), call("u5", 11, 5))
            }
        ) { version ->
            val u1 = undefined("u1", "pa/0")
            val u2 = undefined("u2", "pb/0")
            val u3 = undefined("u3", "m/0")
            val u4 = undefined("u4", "k/0")
            val u5 = undefined("u5", "n/0")

            when {
                isBefore(version, "1.15.0-rc.0") -> listOf(u1, u2, u3, u4, u5)
                isBefore(version, "1.18.0-rc.0") -> listOf(u4, u3, u5, u1, u2)
                isBefore(version, "1.20.0-rc.0") -> listOf(u4, u5, u3, u2, u1)
                else -> listOf(u4, u3, u5, u2, u1)
            }
        }

    @Test
    fun `a call repeated on two lines`() =
        assertOrders(
            module {
                def("a", call("x", 3, 5, label = "x@3"), call("x", 4, 5, label = "x@4"))
            }
        ) { listOf(undefined("x@3", "a/0"), undefined("x@4", "a/0")) }

    @Test
    fun `a call repeated on one line`() =
        assertOrders(
            module {
                def("a", call("x", 2, 15, label = "x@15"), call("x", 2, 20, label = "x@20"))
            }
        ) { version ->
            val first = undefined("x@15", "a/0")
            val second = undefined("x@20", "a/0")

            if (isBefore(version, "1.16.0-rc.0")) listOf(first) else listOf(first, second)
        }

    /**
     * `calculate_span/2` counts a name's graphemes: `x̃` is `x` and U+0303, two code points and one grapheme. The
     * columns are made up, so that only that count orders the two calls.
     */
    @Test
    fun `a name with a combining mark`() =
        assertOrders(
            module {
                def("a", call("x̃", 2, 5), call("abc", 2, 4))
            }
        ) { version ->
            val x = undefined("x̃", "a/0")
            val abc = undefined("abc", "a/0")

            if (isBefore(version, "1.16.0-rc.0")) listOf(abc, x) else listOf(x, abc)
        }

    @Test
    fun `a call in a call's arguments`() =
        assertOrders(
            module {
                def("f", call("b", 2, 14, call("a", 2, 16), arity = 1))
            }
        ) { version ->
            val a = undefined("a", "f/0")
            val b = undefined("b", "f/0")

            when {
                isBefore(version, "1.16.0-rc.0") -> listOf(a, b)
                isBefore(version, "1.18.0-rc.0") -> listOf(b, a)
                isBefore(version, "1.19.0-rc.0") -> listOf(a, b)
                else -> listOf(b, a)
            }
        }

    @Test
    fun `a call in the arguments of a call to a defined function`() =
        assertOrders(
            module {
                def("f", call("k", 2, 14, call("a", 2, 16), arity = 1))
                def("k", call("c", 3, 17, arity = 1), arity = 1)
            }
        ) { version ->
            val a = undefined("a", "f/0")
            val c = undefined("c", "k/1")

            if (isBefore(version, "1.18.0-rc.0")) listOf(a, c) else listOf(c, a)
        }

    @Test
    fun `two definitions whose names sort the other way from their lines`() =
        assertOrders(
            module {
                def("b", call("x", 2, 14))
                def("a", call("y", 3, 14))
            }
        ) { version ->
            val x = undefined("x", "b/0")
            val y = undefined("y", "a/0")

            when {
                isBefore(version, "1.15.0-rc.0") -> listOf(x, y)
                isBefore(version, "1.18.0-rc.0") -> listOf(y, x)
                isBefore(version, "1.20.0-rc.0") -> listOf(x, y)
                else -> listOf(y, x)
            }
        }

    @Test
    fun `a definition whose clauses are apart`() =
        assertOrders(
            module {
                def("b", call("x", 4, 17))
                def("a", call("y", 3, 14))
            }
        ) { version ->
            val x = undefined("x", "b/0")
            val y = undefined("y", "a/0")

            if (isBefore(version, "1.18.0-rc.0") || !isBefore(version, "1.20.0-rc.0")) listOf(y, x) else listOf(x, y)
        }

    @Test
    fun `a macro called before its definition, then an undefined function`() =
        assertOrders(
            module {
                def("f", call("m", 3, 5), call("nope", 4, 5))
                def("m", kind = DEFMACRO)
            }
        ) { listOf(dispatch("m", "f/0"), undefined("nope", "f/0")) }

    @Test
    fun `a function that calls itself`() =
        assertOrders(
            module {
                def("a", call("a", 3, 5), call("x", 4, 5))
            }
        ) { listOf(undefined("x", "a/0")) }

    @Test
    fun `an undefined function in a module that reported an error`() =
        assertOrders(
            module {
                def("f")
                def("g", call("nope", 3, 14))
                tainted = true
            },
            CONTINUING,
        ) { version -> if (isBefore(version, "1.18.0-rc.0")) listOf(undefined("nope", "g/0")) else emptyList() }

    @Test
    fun `a macro called before its definition in a module that reported an error`() =
        assertOrders(
            module {
                def("f", call("m", 2, 14))
                def("g")
                def("m", kind = DEFMACRO)
                tainted = true
            },
            CONTINUING,
        ) { version -> if (isBefore(version, "1.18.0-rc.0")) listOf(dispatch("m", "f/0")) else emptyList() }

    @Test
    fun `a private macro dispatched as a local macro is visited before the other private definitions`() =
        assertOrders(
            module {
                def("pa", call("u1", 2, 19), kind = DEFMACROP)
                def("pb", call("u2", 3, 16), kind = DEFP)
                usedPrivate = listOf(NameArity("pa", 0))
            }
        ) { listOf(undefined("u1", "pa/0"), undefined("u2", "pb/0")) }

    private class Module {
        val kinds = linkedMapOf<NameArity, Kind>()
        val calls = linkedMapOf<NameArity, List<LocalCall<String>>>()
        var usedPrivate = emptyList<NameArity>()
        var tainted = false

        fun def(name: String, vararg calls: LocalCall<String>, arity: Int = 0, kind: Kind = DEF) {
            val nameArity = NameArity(name, arity)
            kinds[nameArity] = kind
            this.calls[nameArity] = calls.toList()
        }
    }

    private fun module(build: Module.() -> Unit) = Module().apply(build)

    private fun call(
        name: String,
        line: Int,
        column: Int,
        vararg arguments: LocalCall<String>,
        arity: Int = 0,
        label: String = name,
    ) = LocalCall(label, NameArity(name, arity), line, column, arguments.toList())

    private fun undefined(at: String, caller: String) = "undefined_function $at in $caller"

    private fun dispatch(at: String, caller: String) = "incorrect_dispatch $at in $caller"

    private fun assertOrders(module: Module, versions: List<String> = LEGS, expected: (String) -> List<String>) =
        assertEquals(
            versions.joinToString("\n") { "$it: ${expected(it).joinToString(", ")}" },
            versions.joinToString("\n") { version ->
                val errors = localErrors(
                    ElixirLanguageLevel.of(version),
                    module.kinds,
                    module.calls,
                    module.usedPrivate,
                    module.tainted,
                )

                "$version: " +
                    errors.joinToString(", ") { "${it.site.kind} ${it.call.at} in ${it.caller.name}/${it.caller.arity}" }
            }
        )

    private fun isBefore(version: String, boundary: String) =
        ElixirLanguageLevel.of(version).elixir < ElixirLanguageLevel.of(boundary).elixir

    private companion object {
        val LEGS = listOf("1.14.5", "1.15.8", "1.16.3", "1.17.3", "1.18.4", "1.19.5", "1.20.4")

        /** The legs where a module can report an error and carry on. */
        val CONTINUING = LEGS - "1.14.5"
    }
}
