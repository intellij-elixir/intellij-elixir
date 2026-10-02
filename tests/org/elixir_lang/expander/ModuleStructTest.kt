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
        val term = list(
            field("major", NIL),
            field("minor", NIL),
            field("patch", NIL),
            field("pre", OtpErlangList()),
            field("build", NIL),
        )

        assertEquals(ModuleStruct.Present(VERSION.fields, Enforced.Unknown), ModuleStruct.from(term))
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

        val VERSION = ModuleStruct.Present(
            setOf("major", "minor", "patch", "pre", "build"),
            Enforced.Known(setOf("major", "minor", "patch")),
        )
    }
}
