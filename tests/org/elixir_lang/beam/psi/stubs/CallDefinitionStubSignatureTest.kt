package org.elixir_lang.beam.psi.stubs

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangMap
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import com.ericsson.otp.erlang.OtpOutputStream
import com.intellij.psi.stubs.SerializationManagerEx
import com.intellij.psi.stubs.Stub
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.beam.BeamBytes
import org.elixir_lang.beam.binaryToTerm
import org.elixir_lang.beam.psi.BeamFileImpl
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Pins each way the decompiler renders a head to a fixture. `SdkStdlibSweepTest` holds the stub to the mirror
 * across whole SDKs.
 */
class CallDefinitionStubSignatureTest : PlatformTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/beam/decompiler"

    // Erlang abstract code

    fun testErlangAbstractCodeNamesComeFromTheFirstClause() {
        assertSignature("queue", "def", "in", 2, listOf("x", "{[_] = erlangVariableIn, []}"), false)
    }

    fun testErlangAbstractCodePatternParameter() {
        assertSignature("queue", "def", "drop", 1, listOf("{[], []} = q"), false)
    }

    fun testErlangAbstractCodePrivateFunction() {
        assertSignature("queue", "defp", "filter_f", 2, listOf("_", "[]"), false)
    }

    fun testPrivateFunctionsOfAModuleWithOverFiveHundredDefinitionsHaveParameters() {
        val stubs = callDefinitionStubs(fileStub("gl"))
        val privates = stubs.filter { !it.isExported && it.callDefinitionClauseHeadArity() > 0 }

        assertTrue("gl.beam has only ${stubs.size} definitions", stubs.size > 500)
        assertTrue("gl.beam has no private definitions with parameters", privates.isNotEmpty())
        assertEmpty(
            "gl.beam's private functions with no parameters",
            privates.filter { it.parameters().isEmpty() }.map { "${it.name}/${it.callDefinitionClauseHeadArity()}" }
        )
    }

    // Elixir debug info

    fun testElixirDebugInfoNamesComeFromTheFirstClause() {
        assertSignature(
            "Elixir.Ecto.Changeset",
            "def",
            "cast",
            4,
            listOf("_data", "%{__struct__: _} = params", "_permitted", "_opts"),
            false
        )
    }

    fun testElixirDebugInfoNamesOfADefinitionWithManyClausesComeFromTheFirstClause() {
        assertSignature(
            "Elixir.Ecto.Query",
            "defp",
            "do_exclude",
            2,
            listOf("%Ecto.Query{} = query", ":join"),
            false
        )
    }

    fun testElixirDebugInfoOperator() {
        assertSignature("Issue575", "def", "&&&", 2, listOf("left", "right"), false)
    }

    // `cast/4`'s first clause has a pattern argument, so its default-argument clause keeps the compiler's names.
    fun testElixirCompilerGeneratedNamesAreFlagged() {
        assertSignature("Elixir.Ecto.Changeset", "def", "cast", 3, listOf("x0", "x1", "x2"), true)
    }

    // Elixir default-argument clauses

    fun testDefaultArgumentClauseTakesNamesFromTheClauseItCalls() {
        assertSignature("Docs/Elixir.Kernel", "defmacro", "def", 1, listOf("call"), false)
    }

    fun testDefaultArgumentClauseOfAFunctionTakesNamesFromTheClauseItCalls() {
        assertSignature("Docs/Elixir.Kernel", "def", "inspect", 1, listOf("term"), false)
    }

    fun testDefinitionsThatCannotBeRenderedKeepTheRestOfTheModule() {
        val (stub, errors) = captureLoggedErrors {
            BeamFileImpl.buildFileStub(
                withUnreadableClauses(beamBytes("Docs/Elixir.Kernel"), "apply" to 3, "put_in" to 3),
                PATH
            )
        }
        assertNotNull("unrenderable definitions dropped the whole module", stub)
        val stubs = callDefinitionStubs(stub!!)

        assertSignature(stubs, "def", "apply", 3, emptyList(), true)
        assertSignature(stubs, "def", "put_in", 3, emptyList(), true)
        assertSignature(stubs, "def", "apply", 2, listOf("fun", "args"), false)
        assertEquals(
            "only the first unrenderable definition in a file is reported, got $errors",
            1,
            errors.count { "$it".contains("parameters of") }
        )
    }

    // Docs chunk signatures

    fun testDocsSignatureNamesMayBeGenerated() {
        val stubs = callDefinitionStubs(stub(withoutDbgi(beamBytes("Docs/Elixir.Kernel"))))

        assertSignature(stubs, "def", "apply", 3, listOf("module", "function_name", "args"), true)
    }

    // `Docs` has only `inspect/2` and `def/2`, whose defaults cover `inspect/1` and `def/1`.
    fun testDocsSignatureOfALowerArityTheDefaultsCover() {
        val stubs = callDefinitionStubs(stub(withoutDbgi(beamBytes("Docs/Elixir.Kernel"))))

        assertSignature(stubs, "def", "inspect", 1, listOf("term"), true)
        assertSignature(stubs, "defmacro", "def", 1, listOf("call"), true)
    }

    // Heads of functions with default arguments

    fun testDocsHeadIsStoredAtEveryArityTheDefaultsCover() {
        val stubs = callDefinitionStubs(stub(withoutDbgi(beamBytes("Docs/Elixir.Kernel"))))

        assertHead(stubs, "def", "inspect", 1, listOf("term", "opts \\\\ []"))
        assertHead(stubs, "def", "inspect", 2, listOf("term", "opts \\\\ []"))
        assertHead(stubs, "defmacro", "def", 1, listOf("call", "expr \\\\ nil"))
        assertHead(stubs, "defmacro", "def", 2, listOf("call", "expr \\\\ nil"))
    }

    fun testDebugInfoHeadIsStoredAtEveryArityTheDefaultClausesCover() {
        val stubs = callDefinitionStubs(stub(withoutDocs(beamBytes("Docs/Elixir.Kernel"))))

        assertHead(stubs, "def", "inspect", 1, listOf("term", "opts \\\\ []"))
        assertHead(stubs, "def", "inspect", 2, listOf("term", "opts \\\\ []"))
        assertHead(stubs, "defmacro", "def", 1, listOf("call", "expr \\\\ nil"))
        assertHead(stubs, "defmacro", "def", 2, listOf("call", "expr \\\\ nil"))
    }

    fun testADefinitionWithoutDefaultsHasNoHead() {
        assertHead(callDefinitionStubs(fileStub("Docs/Elixir.Kernel")), "def", "apply", 3, emptyList())
        assertHead(callDefinitionStubs(fileStub("queue")), "def", "in", 2, emptyList())
    }

    @Suppress("UnstableApiUsage")
    fun testHeadSurvivesSerialization() {
        val built = fileStub("Docs/Elixir.Kernel")
        val output = ByteArrayOutputStream()
        SerializationManagerEx.getInstanceEx().serialize(built, output)
        val deserialized = SerializationManagerEx.getInstanceEx().deserialize(ByteArrayInputStream(output.toByteArray()))

        val expected = callDefinitionStubs(built).map { Triple(it.name, it.callDefinitionClauseHeadArity(), it.head()) }

        assertTrue("Kernel has no stub with a head", expected.any { it.third.isNotEmpty() })
        assertEquals(
            expected,
            callDefinitionStubs(deserialized).map { Triple(it.name, it.callDefinitionClauseHeadArity(), it.head()) }
        )
    }

    // Names the decompiler invents

    fun testCompilerGeneratedFunctionHasGeneratedNames() {
        assertSignature("Elixir.Ecto.Changeset", "def", "__info__", 1, listOf("arg1"), true)
    }

    fun testZeroArityHasNoGeneratedNames() {
        assertSignature("Elixir.Ecto.Changeset", "def", "module_info", 0, emptyList(), false)
    }

    fun testUnquotedNameHasGeneratedNames() {
        assertSignature("Elixir.LDAPEx.ELDAPv3", "defmacro", "AddRequest", 1, listOf("arg1"), true)
    }

    fun testInfixOperatorWithoutDebugInfoHasGeneratedNames() {
        assertSignature("OTP20/Elixir.Kernel", "defmacro", "|>", 2, listOf("left", "right"), true)
    }

    fun testWithoutDebugInfoHasGeneratedNames() {
        assertSignature("OTP20/Elixir.Kernel", "defmacro", "defp", 1, listOf("arg1"), true)
    }

    // Persistence

    @Suppress("UnstableApiUsage")
    fun testSignatureSurvivesSerialization() {
        val built = fileStub("queue")
        val output = ByteArrayOutputStream()
        SerializationManagerEx.getInstanceEx().serialize(built, output)
        val deserialized = SerializationManagerEx.getInstanceEx().deserialize(ByteArrayInputStream(output.toByteArray()))

        val expected = callDefinitionStubs(built).map { Triple(it.name, it.parameters(), it.isAutoGeneratedName) }
        val actual = callDefinitionStubs(deserialized).map { Triple(it.name, it.parameters(), it.isAutoGeneratedName) }

        assertTrue("queue.beam has no stub with parameters", expected.any { it.second.isNotEmpty() })
        assertTrue("queue.beam has no stub with generated names", expected.any { it.third })
        assertEquals(expected, actual)
    }

    private fun assertSignature(
        beam: String,
        macro: String,
        name: String,
        arity: Int,
        parameters: List<String>,
        isAutoGeneratedName: Boolean
    ) {
        assertSignature(callDefinitionStubs(fileStub(beam)), macro, name, arity, parameters, isAutoGeneratedName)
    }

    private fun assertSignature(
        stubs: List<CallDefinitionStub<*>>,
        macro: String,
        name: String,
        arity: Int,
        parameters: List<String>,
        isAutoGeneratedName: Boolean
    ) {
        val stub = stubs.singleOrNull {
            it.resolvedFunctionName() == macro && it.name == name && it.callDefinitionClauseHeadArity() == arity
        }
        assertNotNull(
            "no $macro $name/$arity stub; there are " +
                stubs.joinToString { "${it.resolvedFunctionName()} ${it.name}/${it.callDefinitionClauseHeadArity()}" },
            stub
        )

        assertEquals("$macro $name/$arity parameters", parameters, stub!!.parameters())
        assertEquals("$macro $name/$arity isAutoGeneratedName", isAutoGeneratedName, stub.isAutoGeneratedName)
    }

    private fun assertHead(
        stubs: List<CallDefinitionStub<*>>,
        macro: String,
        name: String,
        arity: Int,
        head: List<String>
    ) {
        val stub = stubs.singleOrNull {
            it.resolvedFunctionName() == macro && it.name == name && it.callDefinitionClauseHeadArity() == arity
        }
        assertNotNull("no $macro $name/$arity stub", stub)

        assertEquals("$macro $name/$arity head", head, stub!!.head())
    }

    private fun beamBytes(beam: String): ByteArray = File(testDataPath, "$beam.beam").readBytes()

    private fun fileStub(beam: String): Stub = stub(beamBytes(beam))

    private fun stub(bytes: ByteArray): Stub = BeamFileImpl.buildFileStub(bytes, PATH) ?: error("no stub built")

    private fun callDefinitionStubs(root: Stub): List<CallDefinitionStub<*>> =
        root.childrenStubs.flatMap { module -> module.childrenStubs.filterIsInstance<CallDefinitionStub<*>>() }

    private fun withoutDbgi(bytes: ByteArray): ByteArray =
        BeamBytes.beam(
            *BeamBytes.chunks(bytes)
                .filter { it.id != "Dbgi" }
                .map { it.id to bytes.copyOfRange(it.data, it.data + it.size) }
                .toTypedArray()
        )

    private fun withoutDocs(bytes: ByteArray): ByteArray =
        BeamBytes.beam(
            *BeamBytes.chunks(bytes)
                .filter { it.id != "Docs" }
                .map { it.id to bytes.copyOfRange(it.data, it.data + it.size) }
                .toTypedArray()
        )

    /** Replaces the Elixir debug info clauses of each name/arity with one whose arguments are not a list. */
    private fun withUnreadableClauses(bytes: ByteArray, vararg nameArities: Pair<String, Int>): ByteArray {
        val spans = BeamBytes.chunks(bytes)
        val dbgi = spans.single { it.id == "Dbgi" }
        val (term, _) = binaryToTerm(bytes.copyOfRange(dbgi.data, dbgi.data + dbgi.size), 0)
        val elixirV1 = (term as OtpErlangTuple).elementAt(2) as OtpErlangTuple
        val map = elixirV1.elementAt(1) as OtpErlangMap
        val definitions = map.get(OtpErlangAtom("definitions")) as OtpErlangList
        val nameArityTerms = nameArities.map { (name, arity) ->
            OtpErlangTuple(arrayOf(OtpErlangAtom(name), OtpErlangLong(arity.toLong())))
        }
        val unreadableClause =
            OtpErlangTuple(arrayOf(OtpErlangList(), OtpErlangAtom("not_a_list"), OtpErlangList(), OtpErlangAtom("nil")))

        var replaced = 0
        val replacedDefinitions = definitions.map { definition ->
            definition as OtpErlangTuple

            if (definition.elementAt(0) in nameArityTerms) {
                replaced++
                OtpErlangTuple(
                    arrayOf(
                        definition.elementAt(0),
                        definition.elementAt(1),
                        definition.elementAt(2),
                        OtpErlangList(unreadableClause)
                    )
                )
            } else {
                definition
            }
        }
        assertEquals("definitions found in the debug info", nameArities.size, replaced)
        map.put(OtpErlangAtom("definitions"), OtpErlangList(replacedDefinitions.toTypedArray<OtpErlangObject>()))

        val stream = OtpOutputStream()
        stream.write_any(term)
        val dbgiData = byteArrayOf(131.toByte()) + stream.toByteArray()

        return BeamBytes.beam(
            *spans.map { span ->
                span.id to if (span === dbgi) dbgiData else bytes.copyOfRange(span.data, span.data + span.size)
            }.toTypedArray()
        )
    }

    private companion object {
        const val PATH = "Elixir.Kernel.beam"
    }
}
