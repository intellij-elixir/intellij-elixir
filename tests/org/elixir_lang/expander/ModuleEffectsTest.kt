package org.elixir_lang.expander

import org.elixir_lang.expander.Effect.*
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.psi.Import.Term
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger

/** What a remote call in [MODULE]'s body does to its attributes, by the call's dispatch and its arguments' values. */
class ModuleEffectsTest {
    @Test
    fun `put_attribute writes the value`() =
        assertEffect("Elixir.Module", "put_attribute", listOf(atom(MODULE), atom("x"), int(1)), Write("x", int(1)))

    @Test
    fun `the put_attribute that @ builds writes the value`() {
        val args = listOf(atom(MODULE), atom("x"), int(1), int(3))

        assertEffect("Elixir.Module", "__put_attribute__", args, Write("x", int(1)), until = "1.14.0-rc.0")
        assertEffect("Elixir.Module", "__put_attribute__", args + Term.List(emptyList()), Write("x", int(1)), "1.14.0-rc.0")
    }

    @Test
    fun `the put_attribute of another leg's arity does nothing`() {
        val args = listOf(atom(MODULE), atom("x"), int(1), int(3))

        assertEffect("Elixir.Module", "__put_attribute__", args, null, "1.14.0-rc.0")
        assertEffect("Elixir.Module", "__put_attribute__", args + Term.List(emptyList()), null, until = "1.14.0-rc.0")
    }

    @Test
    fun `a call on another module does nothing`() {
        assertEffect("Elixir.Module", "put_attribute", listOf(atom("Elixir.Other"), atom("x"), int(1)), null)
        assertEffect("Elixir.Module", "delete_attribute", listOf(atom("Elixir.Other"), atom("x")), null)
    }

    @Test
    fun `a module that isn't known is an unknown effect`() =
        assertEffect("Elixir.Module", "put_attribute", listOf(NODE, atom("x"), int(1)), UnknownEffect)

    @Test
    fun `an attribute that isn't known is an unknown effect`() {
        assertEffect("Elixir.Module", "put_attribute", listOf(atom(MODULE), NODE, int(1)), UnknownEffect)
        assertEffect("Elixir.Module", "delete_attribute", listOf(atom(MODULE), NODE), UnknownEffect)
        assertEffect("Elixir.Module", "register_attribute", listOf(atom(MODULE), NODE, list()), UnknownEffect)
    }

    @Test
    fun `an unknown value is still a write`() =
        assertEffect("Elixir.Module", "put_attribute", listOf(atom(MODULE), atom("x"), NODE), Write("x", NODE))

    @Test
    fun `delete_attribute deletes`() =
        assertEffect("Elixir.Module", "delete_attribute", listOf(atom(MODULE), atom("x")), Delete("x"))

    @Test
    fun `register_attribute accumulates when its accumulate option is truthy`() {
        assertRegister(list(pair("accumulate", atom("true"))), true)
        assertRegister(list(pair("persist", atom("true")), pair("accumulate", atom("true"))), true)
        assertRegister(list(pair("accumulate", int(1))), true)
    }

    @Test
    fun `register_attribute does not accumulate when its accumulate option is absent or falsy`() {
        assertRegister(list(), false)
        assertRegister(list(pair("persist", atom("true"))), false)
        assertRegister(list(pair("accumulate", atom("false"))), false)
        assertRegister(list(pair("accumulate", atom("nil"))), false)
    }

    /** `Keyword.get/2` takes the first `accumulate` pair. */
    @Test
    fun `register_attribute reads the first accumulate option`() =
        assertRegister(list(pair("accumulate", atom("false")), pair("accumulate", atom("true"))), false)

    @Test
    fun `register_attribute whose accumulate option isn't known`() {
        assertRegister(NODE, null)
        assertRegister(list(pair("accumulate", NODE)), null)
        assertRegister(list(NODE, pair("accumulate", atom("true"))), null)
        assertRegister(Term.List(listOf(pair("persist", atom("true"))), NODE), null)
    }

    @Test
    fun `a typespec writes its attribute`() =
        assertEffect(
            "Elixir.Kernel.Typespec",
            "deftypespec",
            listOf(atom("type"), NODE, int(3), Term.Binary("nofile".toByteArray()), atom(MODULE), int(0)),
            TypespecWrite("type"),
        )

    @Test
    fun `other calls do nothing`() {
        assertEffect("Elixir.Module", "get_attribute", listOf(atom(MODULE), atom("x")), null)
        assertEffect("Elixir.String", "length", listOf(Term.Binary("a".toByteArray())), null)
    }

    private fun assertRegister(options: Term, accumulate: Boolean?) =
        assertEffect(
            "Elixir.Module",
            "register_attribute",
            listOf(atom(MODULE), atom("r"), options),
            Register("r", accumulate),
        )

    /** At every leg from [since] up to [until], the call gives [expected]. */
    private fun assertEffect(
        receiver: String,
        name: String,
        args: List<Term>,
        expected: Effect?,
        since: String = "1.11.4",
        until: String? = null,
    ) {
        val legs = LEGS.filter { leg ->
            val elixir = ElixirLanguageLevel.of(leg).elixir

            elixir >= ElixirLanguageLevel.of(since).elixir &&
                (until == null || elixir < ElixirLanguageLevel.of(until).elixir)
        }

        assertEquals(
            legs.joinToString("\n") { "$it: $expected" },
            legs.joinToString("\n") { leg ->
                val dispatch = Dispatch(Dispatch.Kind.REMOTE_FUNCTION, receiver, name, args.size)

                val change = ModuleEffects.of(dispatch, args, MODULE, ElixirLanguageLevel.of(leg))

                "$leg: ${(change as ModuleEffects.Change.Attributes?)?.effect}"
            },
        )
    }

    private companion object {
        const val MODULE = "Elixir.M"

        val LEGS = listOf(
            "1.11.4", "1.12.3", "1.13.4", "1.14.5", "1.15.8", "1.16.3", "1.17.3", "1.18.4", "1.19.5", "1.20.4",
        )

        fun atom(name: String) = Term.Atom(name)

        fun int(value: Int) = Term.Integer(BigInteger.valueOf(value.toLong()))

        fun pair(key: String, value: Term) = Term.Pair(atom(key), value)

        fun list(vararg elements: Term) = Term.List(elements.toList())
    }
}
