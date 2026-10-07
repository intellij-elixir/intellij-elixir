package org.elixir_lang.expander

import org.elixir_lang.expander.Effect.*
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.psi.Import.Term
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger

/**
 * A module's attributes as its body's calls leave them, over hand-built effects: what a definition reads at each
 * point, and which writes Elixir refuses.
 */
class AttributeTableTest {
    @Test
    fun `a definition reads the value written before it`() =
        assertTrace(
            "1.20.4",
            Write("x", int(1)) to null,
            read("x") to "1",
            Write("x", int(2)) to null,
            read("x") to "2",
        )

    @Test
    fun `an attribute never written reads nil`() = assertTrace("1.20.4", read("x") to ":nil")

    @Test
    fun `a registered attribute never written reads nil`() =
        assertTrace("1.20.4", Register("r", accumulate = false) to null, read("r") to ":nil")

    @Test
    fun `an accumulating attribute reads newest first`() =
        assertTrace(
            "1.20.4",
            Register("acc", accumulate = true) to null,
            read("acc") to "[]",
            Write("acc", int(1)) to null,
            read("acc") to "[1]",
            Write("acc", int(2)) to null,
            read("acc") to "[2, 1]",
        )

    @Test
    fun `registering a written attribute as accumulating loses its value`() =
        assertTrace(
            "1.20.4",
            Write("x", int(1)) to null,
            Register("x", accumulate = true) to null,
            read("x") to "[]",
        )

    @Test
    fun `registering a written attribute without accumulate keeps its value`() =
        assertTrace(
            "1.20.4",
            Write("x", int(1)) to null,
            Register("x", accumulate = false) to null,
            read("x") to "1",
        )

    @Test
    fun `a deleted attribute reads nil`() =
        assertTrace("1.20.4", Write("x", int(1)) to null, Delete("x") to null, read("x") to ":nil")

    @Test
    fun `a deleted accumulating attribute stays accumulating`() =
        assertTrace(
            "1.20.4",
            Register("acc", accumulate = true) to null,
            Write("acc", int(1)) to null,
            Delete("acc") to null,
            read("acc") to "[]",
            Write("acc", int(2)) to null,
            read("acc") to "[2]",
        )

    @Test
    fun `the attributes every leg accumulates`() =
        everyLeg {
            assertTrace(
                it,
                *ALWAYS_ACCUMULATING.map { name -> read(name) to if (name == "on_definition") ON_DEFINITION else "[]" }
                    .toTypedArray(),
            )
        }

    @Test
    fun `@after_verify accumulates from 1_14`() {
        assertTrace(
            "1.13.4",
            read("after_verify") to ":nil",
            Write("after_verify", atom("Elixir.A")) to null,
            Write("after_verify", atom("Elixir.B")) to null,
            read("after_verify") to ":Elixir.B",
        )
        assertTrace(
            "1.14.0-rc.0",
            read("after_verify") to "[]",
            Write("after_verify", atom("Elixir.A")) to null,
            Write("after_verify", atom("Elixir.B")) to null,
            read("after_verify") to "[{:Elixir.B, :__after_verify__}, {:Elixir.A, :__after_verify__}]",
        )
    }

    @Test
    fun `@nifs accumulates from 1_19`() {
        val first = Write("nifs", list(pair(atom("f"), int(0))))
        val second = Write("nifs", list(pair(atom("g"), int(0))))

        assertTrace("1.18.4", read("nifs") to ":nil", first to null, second to null, read("nifs") to "[{:g, 0}]")
        assertTrace(
            "1.19.0-rc.0",
            read("nifs") to "[]",
            first to null,
            second to null,
            read("nifs") to "[[{:g, 0}], [{:f, 0}]]",
        )
    }

    @Test
    fun `@on_definition starts with Elixir's own callback`() =
        assertTrace(
            "1.20.4",
            Write("on_definition", atom("Elixir.C")) to null,
            read("on_definition") to "[{:Elixir.C, :__on_definition__}, $ON_DEFINITION_ENTRY]",
        )

    @Test
    fun `@moduledoc starts nil`() = everyLeg { assertTrace(it, read("moduledoc") to ":nil") }

    @Test
    fun `a module named by a callback attribute gets its callback's name`() =
        everyLeg {
            assertTrace(
                it,
                Write("before_compile", atom("Elixir.B")) to null,
                Write("after_compile", atom("Elixir.A")) to null,
                Write("before_compile", pair(atom("Elixir.B"), atom("f"))) to null,
                read("before_compile") to "[{:Elixir.B, :f}, {:Elixir.B, :__before_compile__}]",
                read("after_compile") to "[{:Elixir.A, :__after_compile__}]",
            )
        }

    @Test
    fun `@on_load takes an atom or an atom and zero, once`() =
        everyLeg {
            assertTrace(it, Write("on_load", atom("init")) to null, read("on_load") to "{:init, 0}")
            assertTrace(it, Write("on_load", pair(atom("init"), int(0))) to null, read("on_load") to "{:init, 0}")
            assertTrace(it, Write("on_load", pair(atom("init"), int(1))) to INVALID)
            assertTrace(it, Write("on_load", binary("init")) to INVALID)
            assertTrace(it, Write("on_load", atom("a")) to null, Write("on_load", atom("b")) to INVALID)
        }

    @Test
    fun `a second @on_load raises whatever its value`() =
        assertTrace("1.20.4", Write("on_load", atom("a")) to null, Write("on_load", NODE) to INVALID)

    /** An `@on_load` write that may not have run may or may not make the next one the second. */
    @Test
    fun `an @on_load after one that is not a statement or an unknown effect is not checked`() {
        assertTrace(
            "1.20.4",
            elsewhere(Write("on_load", atom("a"))) to UNCHECKED,
            Write("on_load", atom("b")) to UNCHECKED,
        )
        assertTrace("1.20.4", elsewhere(UnknownEffect) to null, Write("on_load", atom("b")) to UNCHECKED)
    }

    /** An `@on_load` write that isn't a statement may run more than once, and its second run raises. */
    @Test
    fun `an @on_load that is not a statement is not checked`() =
        everyLeg { assertTrace(it, elsewhere(Write("on_load", atom("a"))) to UNCHECKED) }

    /** The value is checked before the attribute is looked up, so an invalid one raises after any earlier write. */
    @Test
    fun `an invalid @on_load raises after one that is not a statement`() =
        everyLeg {
            assertTrace(it, elsewhere(Write("on_load", atom("a"))) to UNCHECKED, Write("on_load", int(1)) to INVALID)
            assertTrace(it, elsewhere(UnknownEffect) to null, Write("on_load", int(1)) to INVALID)
        }

    @Test
    fun `a value with no exact term reads as a node`() =
        assertTrace("1.20.4", Write("x", list(int(1), NODE)) to null, read("x") to "node")

    @Test
    fun `an accumulating attribute with one unknown write reads as a node`() =
        assertTrace(
            "1.20.4",
            Write("compile", atom("a")) to null,
            Write("compile", NODE) to null,
            Write("compile", atom("b")) to null,
            read("compile") to "node",
        )

    @Test
    fun `an unknown value written to a checked attribute is not checked`() =
        everyLeg {
            for (name in listOf("impl", "deprecated", "doc", "external_resource", "file", "on_load")) {
                assertTrace(it, Write(name, NODE) to UNCHECKED, read(name) to "node")
            }
        }

    @Test
    fun `an unknown effect makes every later read unknown`() =
        assertTrace(
            "1.20.4",
            Write("x", int(1)) to null,
            UnknownEffect to null,
            read("x") to "node",
            read("never") to "node",
        )

    @Test
    fun `a write that is not a statement makes later reads of its attribute unknown`() =
        assertTrace(
            "1.20.4",
            Write("x", int(1)) to null,
            Write("y", int(1)) to null,
            elsewhere(Write("x", int(2))) to null,
            read("x") to "node",
            Write("x", int(3)) to null,
            read("x") to "node",
            read("y") to "1",
        )

    @Test
    fun `a register or delete that is not a statement makes later reads of its attribute unknown`() =
        assertTrace(
            "1.20.4",
            elsewhere(Register("r", accumulate = true)) to null,
            elsewhere(Delete("d")) to null,
            read("r") to "node",
            read("d") to "node",
        )

    @Test
    fun `a write to a checked attribute that is not a statement is not checked`() =
        assertTrace("1.20.4", elsewhere(Write("impl", binary("x"))) to UNCHECKED)

    @Test
    fun `an unknown effect that is not a statement makes every later read unknown`() =
        assertTrace("1.20.4", elsewhere(UnknownEffect) to null, read("x") to "node")

    @Test
    fun `a register whose accumulate option is unknown makes its attribute unknown`() =
        assertTrace("1.20.4", Register("r", accumulate = null) to null, read("r") to "node")

    @Test
    fun `a typespec is an unknown value on its attribute`() =
        everyLeg {
            assertTrace(
                it,
                *TYPESPECS.flatMap { name -> listOf(TypespecWrite(name) to null, read(name) to "node") }.toTypedArray(),
            )
        }

    @Test
    fun `a typespec attribute written with put_attribute raises`() =
        everyLeg { assertTrace(it, *TYPESPECS.map { name -> Write(name, NODE) to INVALID }.toTypedArray()) }

    @Test
    fun `a doc reads as its text`() =
        everyLeg {
            for (name in listOf("doc", "moduledoc", "typedoc")) {
                assertTrace(
                    it,
                    Write(name, pair(int(3), binary("hi"))) to null,
                    read(name) to "\"hi\"",
                    Write(name, pair(int(4), atom("false"))) to null,
                    read(name) to ":false",
                    Write(name, pair(int(5), atom("nil"))) to null,
                    read(name) to ":nil",
                )
            }
        }

    @Test
    fun `doc metadata leaves the doc as it was`() =
        everyLeg {
            assertTrace(
                it,
                Write("doc", pair(int(3), binary("hi"))) to null,
                Write("doc", pair(int(4), list(pair(atom("since"), binary("1.0"))))) to null,
                read("doc") to "\"hi\"",
            )
        }

    @Test
    fun `a doc that is not text, false, nil or metadata raises`() =
        everyLeg {
            assertTrace(it, Write("doc", pair(int(3), int(1))) to INVALID)
            assertTrace(it, Write("doc", binary("set dynamically without a line")) to INVALID)
        }

    @Test
    fun `an empty doc keyword list is metadata from 1_14`() {
        assertTrace("1.13.4", Write("doc", pair(int(3), list())) to INVALID)
        assertTrace("1.14.0-rc.0", Write("doc", pair(int(3), list())) to null, read("doc") to ":nil")
    }

    @Test
    fun `doc metadata is checked`() =
        everyLeg {
            assertTrace(it, Write("doc", pair(int(3), list(pair(atom("since"), int(1))))) to INVALID)
            assertTrace(it, Write("doc", pair(int(3), list(pair(atom("deprecated"), int(1))))) to INVALID)
            assertTrace(it, Write("doc", pair(int(3), list(pair(atom("delegate_to"), atom("f"))))) to INVALID)
            assertTrace(it, Write("doc", pair(int(3), list(pair(atom("opaque"), int(1))))) to null)
        }

    /** `preprocess_doc_meta/4` has no clause for an element that isn't a pair with an atom key, so it fails there. */
    @Test
    fun `doc metadata with an element that isn't a key and value is not checked`() =
        everyLeg {
            val since = pair(atom("since"), binary("1.0"))

            assertTrace(it, Write("doc", pair(int(3), list(since, int(1)))) to UNCHECKED)
            assertTrace(it, Write("doc", pair(int(3), list(pair(atom("since"), int(1)), int(1)))) to INVALID)
            assertTrace(it, Write("doc", pair(int(3), Term.List(listOf(since), int(1)))) to UNCHECKED)
            // Before 1.14 a list whose first element isn't a key and value, such as a charlist, is checked as a doc.
            val firstNotMetadata = if (ElixirLanguageLevel.of(it).elixir < ElixirLanguageLevel.of("1.14.0").elixir) {
                INVALID
            } else {
                UNCHECKED
            }

            assertTrace(it, Write("doc", pair(int(3), list(int(1), since))) to firstNotMetadata)
            assertTrace(it, Write("doc", pair(int(3), list(int(120)))) to firstNotMetadata)
        }

    @Test
    fun `@impl takes a boolean or a module`() =
        everyLeg {
            assertTrace(it, Write("impl", atom("true")) to null, read("impl") to ":true")
            assertTrace(it, Write("impl", atom("false")) to null, read("impl") to ":false")
            assertTrace(it, Write("impl", atom("Elixir.B")) to null, read("impl") to ":Elixir.B")
            assertTrace(it, Write("impl", atom("nil")) to INVALID)
            assertTrace(it, Write("impl", binary("B")) to INVALID)
        }

    @Test
    fun `@behaviour takes a module from 1_13`() {
        assertTrace("1.12.3", Write("behaviour", binary("B")) to null, read("behaviour") to "[\"B\"]")
        assertTrace("1.13.0-rc.0", Write("behaviour", binary("B")) to INVALID)
        assertTrace("1.13.0-rc.0", Write("behaviour", atom("Elixir.B")) to null, read("behaviour") to "[:Elixir.B]")
    }

    @Test
    fun `@deprecated takes text`() =
        everyLeg {
            assertTrace(it, Write("deprecated", binary("use g")) to null, read("deprecated") to "\"use g\"")
            assertTrace(it, Write("deprecated", atom("true")) to INVALID)
        }

    @Test
    fun `@external_resource takes text`() =
        everyLeg {
            assertTrace(it, Write("external_resource", binary("p")) to null, read("external_resource") to "[\"p\"]")
            assertTrace(it, Write("external_resource", atom("p")) to INVALID)
        }

    @Test
    fun `@file takes text, or text and a line`() =
        everyLeg {
            assertTrace(it, Write("file", binary("f")) to null)
            assertTrace(it, Write("file", pair(binary("f"), int(1))) to null, read("file") to "{\"f\", 1}")
            assertTrace(it, Write("file", pair(binary("f"), atom("a"))) to INVALID)
            assertTrace(it, Write("file", atom("f")) to INVALID)
        }

    @Test
    fun `@dialyzer is checked from 1_12`() {
        val bad = Write("dialyzer", atom("bogus"))

        assertTrace("1.11.4", bad to null, read("dialyzer") to "[:bogus]")
        assertTrace("1.12.0-rc.0", bad to INVALID)

        everyLeg("1.12.0-rc.0") {
            assertTrace(it, Write("dialyzer", atom("no_return")) to null)
            assertTrace(it, Write("dialyzer", list(atom("no_match"), atom("unknown"))) to null)
            assertTrace(it, Write("dialyzer", pair(atom("nowarn_function"), list(pair(atom("f"), int(0))))) to null)
            assertTrace(it, Write("dialyzer", pair(atom("no_return"), pair(atom("f"), int(0)))) to null)
            assertTrace(it, Write("dialyzer", pair(atom("nowarn_function"), pair(atom("f"), atom("x")))) to INVALID)
            assertTrace(it, Write("dialyzer", pair(atom("bogus"), list())) to INVALID)
            // `List.wrap(nil)` is `[]`, so `nil` names no option and no function, but `[nil]` names `nil`.
            assertTrace(it, Write("dialyzer", atom("nil")) to null)
            assertTrace(it, Write("dialyzer", pair(atom("nowarn_function"), atom("nil"))) to null)
            assertTrace(it, Write("dialyzer", list(atom("nil"))) to INVALID)
        }
    }

    @Test
    fun `@nifs is checked from 1_16`() {
        val bad = Write("nifs", list(pair(atom("f"), atom("a"))))

        assertTrace("1.15.8", bad to null)
        assertTrace("1.16.0-rc.0", bad to INVALID)
        assertTrace("1.16.0-rc.0", Write("nifs", atom("f")) to INVALID)
    }

    /**
     * `:lists.foreach/2` and `:lists.all/2` stop at the first invalid element, and otherwise reach an improper list's
     * tail, whose `FunctionClauseError` isn't modelled.
     */
    @Test
    fun `an improper @dialyzer or @nifs list is not checked past its elements`() {
        val f0 = pair(atom("f"), int(0))

        everyLeg("1.12.0-rc.0") {
            assertTrace(it, Write("dialyzer", Term.List(listOf(atom("no_return")), atom("x"))) to UNCHECKED)
            assertTrace(it, Write("dialyzer", Term.List(listOf(atom("bogus")), atom("x"))) to INVALID)
            assertTrace(
                it,
                Write("dialyzer", pair(atom("nowarn_function"), Term.List(listOf(f0), atom("x")))) to UNCHECKED,
            )
        }
        everyLeg("1.16.0-rc.0") {
            assertTrace(it, Write("nifs", Term.List(listOf(f0), atom("x"))) to UNCHECKED)
            assertTrace(it, Write("nifs", Term.List(listOf(int(1)), atom("x"))) to INVALID)
        }
    }

    @Test
    fun `the final table is what the module body leaves`() {
        val table = AttributeTable(ElixirLanguageLevel.of("1.20.4"))

        table.apply(Write("x", int(1)))
        table.apply(Write("compile", atom("a")))
        table.apply(Write("compile", atom("b")))
        table.apply(Write("y", NODE))
        table.apply(Register("r", accumulate = false))

        assertEquals(
            listOf("compile: [:b, :a]", "r: :nil", "x: 1", "y: unknown"),
            table.final.filterKeys { it in setOf("x", "y", "compile", "r") }
                .map { (name, value) -> "$name: ${render(value)}" }
                .sorted(),
        )
    }

    /** `lookup_attribute`, as the checks after the module body read an attribute: each value, oldest first. */
    @Test
    fun `the values the checks after the module body read`() {
        val table = AttributeTable(ElixirLanguageLevel.of("1.20.4"))

        table.apply(Write("x", int(1)))
        table.apply(Write("x", int(2)))
        table.apply(Write("compile", atom("a")))
        table.apply(Write("compile", atom("b")))
        table.apply(Register("r", accumulate = false))
        table.apply(Write("y", NODE))
        table.apply(Write("z", int(3)), statement = false)

        assertEquals(
            listOf("x: [2]", "compile: [:a, :b]", "r: []", "absent: []", "y: [unknown]", "z: [unknown]"),
            listOf("x", "compile", "r", "absent", "y", "z").map { name ->
                "$name: ${table.values(name).joinToString(", ", "[", "]") { render(it) }}"
            },
        )
    }

    @Test
    fun `after an unknown effect every attribute has an unknown value`() {
        val table = AttributeTable(ElixirLanguageLevel.of("1.20.4"))

        table.apply(Effect.UnknownEffect)

        assertEquals(listOf(AttributeValue.Unknown), table.values("absent"))
    }

    /** Applies each step in turn: an effect expecting its outcome (`null` when stored), or a read expecting a value. */
    private fun assertTrace(version: String, vararg steps: Pair<Any, String?>) {
        val table = AttributeTable(ElixirLanguageLevel.of(version))
        val actual = steps.map { (step, _) ->
            when (step) {
                is Read -> "read ${step.name} = ${render(table.read(step.name))}"
                is Elsewhere -> "${step.effect} (not a statement) -> ${outcome(table.apply(step.effect, statement = false))}"
                is Effect -> "$step -> ${outcome(table.apply(step))}"
                else -> throw IllegalArgumentException("$step")
            }
        }
        val expected = steps.map { (step, expected) ->
            when (step) {
                is Read -> "read ${step.name} = $expected"
                is Elsewhere -> "${step.effect} (not a statement) -> ${expected ?: STORED}"
                else -> "$step -> ${expected ?: STORED}"
            }
        }

        assertEquals("on $version", expected.joinToString("\n"), actual.joinToString("\n"))
    }

    private data class Read(val name: String)

    private data class Elsewhere(val effect: Effect)

    private fun read(name: String) = Read(name)

    private fun elsewhere(effect: Effect) = Elsewhere(effect)

    private fun outcome(outcome: EffectOutcome): String =
        when (outcome) {
            EffectOutcome.Stored -> STORED
            EffectOutcome.Unchecked -> UNCHECKED
            is EffectOutcome.Raises -> "raises ${outcome.kind}"
        }

    private fun everyLeg(from: String = "1.11.4", test: (String) -> Unit) =
        LEGS.filter { ElixirLanguageLevel.of(it).elixir >= ElixirLanguageLevel.of(from).elixir }.forEach(test)

    private fun render(value: AttributeValue): String =
        when (value) {
            is AttributeValue.Known -> render(value.term)
            AttributeValue.Unknown -> "unknown"
        }

    private fun render(term: Term): String =
        when (term) {
            is Term.Atom -> ":${term.name}"
            is Term.Integer -> term.value.toString()
            is Term.Binary -> term.bytes?.let { "\"${String(it)}\"" } ?: "binary"
            is Term.List ->
                term.elements.joinToString(", ", "[", "") { render(it) } +
                    (term.tail?.let { " | ${render(it)}" } ?: "") + "]"
            is Term.Pair -> "{${render(term.first)}, ${render(term.second)}}"
            is Term.Node -> "node"
            Term.NonTuple -> "non-tuple"
            Term.Unexpanded -> "unexpanded"
        }

    private fun atom(name: String) = Term.Atom(name)

    private fun int(value: Int) = Term.Integer(BigInteger.valueOf(value.toLong()))

    private fun binary(text: String) = Term.Binary(text.toByteArray())

    private fun list(vararg elements: Term) = Term.List(elements.toList())

    private fun pair(first: Term, second: Term) = Term.Pair(first, second)

    private companion object {
        const val STORED = "stored"
        const val UNCHECKED = "unchecked"
        const val INVALID = "raises invalid_attribute_value"
        const val ON_DEFINITION_ENTRY = "{:Elixir.Module, :compile_definition_attributes}"
        const val ON_DEFINITION = "[$ON_DEFINITION_ENTRY]"

        val LEGS =
            listOf("1.11.4", "1.12.3", "1.13.4", "1.14.5", "1.15.8", "1.16.3", "1.17.3", "1.18.4", "1.19.5", "1.20.4")

        val TYPESPECS = listOf("type", "typep", "opaque", "spec", "callback", "macrocallback")

        val ALWAYS_ACCUMULATING =
            listOf(
                "after_compile", "before_compile", "behaviour", "compile", "derive", "dialyzer", "external_resource",
                "on_definition", "optional_callbacks",
            ) + TYPESPECS
    }
}
