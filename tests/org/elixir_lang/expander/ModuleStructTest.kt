package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangMap
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import org.junit.Assert.assertEquals
import org.junit.Test

/** The `:struct` value of an `elixir_v1` `Dbgi` map, in each shape an Elixir release writes, as `Version`'s. */
class ModuleStructTest {
    @Test
    fun `up to 1_13 the default struct and the enforced keys`() {
        val default = map(
            "__struct__" to atom("Elixir.Version"),
            "major" to NIL,
            "minor" to NIL,
            "patch" to NIL,
            "pre" to OtpErlangList(),
            "build" to NIL,
        )
        val term = OtpErlangTuple(arrayOf(default, list(atom("major"), atom("minor"), atom("patch"))))

        assertEquals(VERSION, ModuleStruct.from(term))
    }

    @Test
    fun `from 1_14 each field with whether it is required`() {
        val term = list(
            field("major", NIL, true),
            field("minor", NIL, true),
            field("patch", NIL, true),
            field("pre", OtpErlangList(), false),
            field("build", NIL, false),
        )

        assertEquals(VERSION, ModuleStruct.from(term))
    }

    @Test
    fun `on 1_18 each field without whether it is required`() {
        assertEquals(ModuleStruct.Present(VERSION.fields, Enforced.Unknown), ModuleStruct.from(FIELDS_1_18))
    }

    @Test
    fun `on 1_18 no enforced keys where __struct__-1 reduces into the struct`() {
        val body = call("Elixir.Enum", "reduce", variable("kv"), STRUCT, variable("fun"))

        assertEquals(
            ModuleStruct.Present(VERSION.fields, Enforced.Known(emptySet())),
            ModuleStruct.from(FIELDS_1_18, definitions(body)),
        )
    }

    @Test
    fun `on 1_18 the enforced keys __struct__-1 reduces into beside the struct`() {
        val keys = list(atom("major"), atom("minor"), atom("patch"))
        val reduce = call("Elixir.Enum", "reduce", variable("kv"), OtpErlangTuple(arrayOf(STRUCT, keys)), variable("fun"))
        val match = node("=", OtpErlangTuple(arrayOf(variable("map"), variable("keys"))), reduce)
        val body = node("__block__", match, node("case", variable("keys"), list()))

        assertEquals(VERSION, ModuleStruct.from(FIELDS_1_18, definitions(body)))
    }

    @Test
    fun `on 1_18 a __struct__-1 that doesn't check the keys it reduces leaves the enforced keys unknown`() {
        val keys = list(atom("major"), atom("minor"), atom("patch"))
        val reduce = call("Elixir.Enum", "reduce", variable("kv"), OtpErlangTuple(arrayOf(STRUCT, keys)), variable("fun"))
        val match = node("=", OtpErlangTuple(arrayOf(variable("map"), variable("keys"))), reduce)

        for (body in listOf(
            node("__block__", match, variable("map")),
            node("__block__", match, node("case", variable("map"), list())),
            node("__block__", match, node("case", variable("keys"), list()), variable("map")),
        )) {
            assertEquals(
                ModuleStruct.Present(VERSION.fields, Enforced.Unknown),
                ModuleStruct.from(FIELDS_1_18, definitions(body)),
            )
        }
    }

    @Test
    fun `on 1_18 a __struct__-1 of another shape leaves the enforced keys unknown`() {
        val body = call("lists", "foldl", variable("fun"), STRUCT, variable("kv"))

        assertEquals(
            ModuleStruct.Present(VERSION.fields, Enforced.Unknown),
            ModuleStruct.from(FIELDS_1_18, definitions(body)),
        )
    }

    @Test
    fun `no fields enforce no keys`() {
        assertEquals(ModuleStruct.Present(emptySet(), Enforced.Known(emptySet())), ModuleStruct.from(OtpErlangList()))
    }

    @Test
    fun `a term in none of the shapes is unreadable`() {
        assertEquals(ModuleStruct.Unreadable, ModuleStruct.from(NIL))
        assertEquals(ModuleStruct.Unreadable, ModuleStruct.from(OtpErlangLong(1)))
        assertEquals(ModuleStruct.Unreadable, ModuleStruct.from(list(atom("major"))))
        assertEquals(ModuleStruct.Unreadable, ModuleStruct.from(list(map("default" to NIL))))
        assertEquals(ModuleStruct.Unreadable, ModuleStruct.from(list(field("major", NIL, true), field("pre", NIL))))
        assertEquals(
            ModuleStruct.Unreadable,
            ModuleStruct.from(OtpErlangTuple(arrayOf(map("__struct__" to atom("Elixir.Version")), list(OtpErlangLong(1)))))
        )
    }

    /** The `definitions` of a `Dbgi` map holding only `__struct__/1`, whose one clause is [body]. */
    private fun definitions(body: OtpErlangObject) =
        list(
            OtpErlangTuple(
                arrayOf(
                    OtpErlangTuple(arrayOf(atom("__struct__"), OtpErlangLong(1))),
                    atom("def"),
                    list(),
                    list(OtpErlangTuple(arrayOf(list(), list(variable("kv")), list(), body))),
                ),
            ),
        )

    private fun call(module: String, function: String, vararg args: OtpErlangObject) =
        OtpErlangTuple(arrayOf(node(".", atom(module), atom(function)), list(), list(*args)))

    private fun node(name: String, vararg args: OtpErlangObject) = OtpErlangTuple(arrayOf(atom(name), list(), list(*args)))

    private fun variable(name: String) = OtpErlangTuple(arrayOf(atom(name), list(), atom("Elixir.Kernel.Utils")))

    private fun field(name: String, default: OtpErlangObject, required: Boolean? = null): OtpErlangMap {
        val entries = listOf("default" to default, "field" to atom(name)) +
            listOfNotNull(required?.let { "required" to atom(it.toString()) })

        return map(*entries.toTypedArray())
    }

    private fun map(vararg entries: Pair<String, OtpErlangObject>) =
        OtpErlangMap(
            entries.map { atom(it.first) }.toTypedArray<OtpErlangObject>(),
            entries.map { it.second }.toTypedArray()
        )

    private fun list(vararg elements: OtpErlangObject) = OtpErlangList(elements)

    private fun atom(name: String) = OtpErlangAtom(name)

    private companion object {
        val NIL = OtpErlangAtom("nil")

        val FIELDS_1_18 = listOf("major" to NIL, "minor" to NIL, "patch" to NIL, "pre" to OtpErlangList(), "build" to NIL)
            .map { (name, default) ->
                OtpErlangMap(arrayOf(OtpErlangAtom("default"), OtpErlangAtom("field")), arrayOf(default, OtpErlangAtom(name)))
            }
            .let { OtpErlangList(it.toTypedArray<OtpErlangObject>()) }

        /** The escaped struct `defstruct` gives `__struct__/1`'s body, as `Version`'s. */
        val STRUCT = OtpErlangTuple(
            arrayOf(
                OtpErlangAtom("%{}"),
                OtpErlangList(),
                OtpErlangList(
                    listOf("__struct__" to OtpErlangAtom("Elixir.Version"), "build" to NIL, "major" to NIL)
                        .map { (key, value) -> OtpErlangTuple(arrayOf(OtpErlangAtom(key), value)) }
                        .toTypedArray<OtpErlangObject>(),
                ),
            ),
        )

        val VERSION = ModuleStruct.Present(
            setOf("major", "minor", "patch", "pre", "build"),
            Enforced.Known(setOf("major", "minor", "patch")),
        )
    }
}
