package org.elixir_lang.beam.chunk.debug_info

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBinary
import com.intellij.psi.stubs.Stub
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.beam.decompiler.ReservedTypeName
import org.elixir_lang.expander.failure
import org.elixir_lang.expander.legBeam
import org.elixir_lang.model.psi.type.TypeBuiltins
import org.elixir_lang.intellij_elixir.Quoter
import org.elixir_lang.beam.psi.BeamFileImpl
import org.elixir_lang.beam.psi.impl.ModuleImpl
import org.elixir_lang.beam.psi.impl.ModuleStubImpl
import org.elixir_lang.beam.psi.stubs.ModuleStub
import org.elixir_lang.beam.psi.stubs.TypeDefinitionStub
import org.elixir_lang.beam.psi.stubs.serializedAndBack
import org.elixir_lang.psi.stub.impl.ElixirFileStubImpl
import org.elixir_lang.type.Visibility
import java.io.File
import kotlin.time.Duration.Companion.seconds

/** Whether a `.beam`'s module stub says its types were read, as built and as read back from the index. */
class TypeDefinitionsTest : PlatformTestCase() {
    fun testAnElixirCompiledModulesTypesAreRead() {
        assertTypesRead(MODULE_EBIN, "Elixir.Code", Triple(Visibility.PUBLIC, "binding", 0))
    }

    /** `@typep`s are in the debug info's specs though no docs entry names them. */
    fun testAnElixirModulesPrivateTypesAreRead() {
        assertTypesRead(
            MODULE_BEAMS,
            "Elixir.Module",
            Triple(Visibility.PRIVATE, "def_kind", 0),
            Triple(Visibility.PRIVATE, "definition", 0),
        )
    }

    fun testAProtocolsTypesAreRead() {
        assertTypesRead(MODULE_EBIN, "Elixir.Inspect", Triple(Visibility.PUBLIC, "t", 0))
    }

    /**
     * `Protocol.consolidate` keeps the specs of the protocol it rewrites. The quoter's own `String.Chars` is stripped of
     * debug info, so a protocol compiled here is consolidated instead.
     */
    fun testAConsolidatedProtocolsTypesAreRead() {
        val compiled = Quoter.compile(CONSOLIDATED_PROBE, 120.seconds)
        assertEquals("compile: ${compiled.failure}", OtpErlangAtom("ok"), compiled.status)

        val bytes = (compiled.messages.single() as OtpErlangBinary).binaryValue()
        val module = module(BeamFileImpl.buildFileStub(bytes, "Elixir.ConsolidateProbe.beam")!!)

        assertTrue("ConsolidateProbe's types read", module.typesRead())
        assertEquals(
            listOf("extra/0", "t/0"),
            types(module).map { "${it.name}/${it.arity}" }.sorted(),
        )
    }

    fun testAnErlangModulesTypesAreReadFromItsAbstractCode() = assertTypes(TYPE_EBIN, "queue", read = true, count = 2)

    /** `erlc` without `debug_info` writes `none` for the abstract code, so the types it defines aren't known. */
    fun testAnErlangModuleCompiledWithoutDebugInfosTypesAreNotRead() =
        assertTypes(UNREAD_TYPES_EBIN, "no_debug_info", read = false, count = 0)

    /** `erlc` compiling Core Erlang writes no forms for the abstract code, so the types it defines aren't known. */
    fun testAnErlangModuleCompiledFromCoreErlangsTypesAreNotRead() =
        assertTypes(UNREAD_TYPES_EBIN, "from_core", read = false, count = 0)

    /** `erlang` declares its own types, which the built-ins it doesn't declare are added to. */
    fun testErlangsTypesAreItsAbstractCodesAndTheBuiltins() {
        assertTypesRead(
            TYPE_EBIN,
            "erlang",
            Triple(Visibility.PUBLIC, "timestamp", 0),
            Triple(Visibility.OPAQUE, "prepared_code", 0),
            Triple(Visibility.PRIVATE, "ext_binary", 0),
            Triple(Visibility.PUBLIC, "keyword", 1),
        )
    }

    /** Whichever built-ins the leg's `erlang` declares, each pair is there once. */
    fun testTheLegsErlangKeepsEveryBuiltinPair() {
        val beam = legBeam("erlang")!!
        val module = module(stub(beam))
        val pairs = types(module).map { it.name to it.arity }

        for ((name, arities) in TypeBuiltins.BUILTIN_ARITY_BY_NAME) {
            if (ReservedTypeName.isReserved(name)) continue

            for (arity in arities) assertTrue("$name/$arity among erlang's types", (name to arity) in pairs)
        }

        assertTrue("dynamic/1", ("dynamic" to 1) in pairs)
        assertTrue("nonempty_improper_list/0", ("nonempty_improper_list" to 0) in pairs)
        assertEquals("each pair once", pairs.size, pairs.toSet().size)
    }

    /** A module stub is built not read, as the minimal tree, built where the index has none, builds its module's. */
    fun testAModuleStubIsBuiltWithItsTypesNotRead() {
        val module = ModuleStubImpl<ModuleImpl<*>>(ElixirFileStubImpl(), "Minimal", "Minimal")

        assertFalse(module.typesRead())
        assertFalse(module(serializedAndBack(module.parentStub)).typesRead())
    }

    private fun assertTypes(ebin: File, beam: String, read: Boolean, count: Int) {
        val built = stub(File(ebin, "$beam.beam"))

        for ((which, root) in listOf("built" to built, "deserialized" to serializedAndBack(built))) {
            val module = module(root)

            assertEquals("$beam's types read, $which", read, module.typesRead())
            assertEquals("$beam's type stubs, $which", count, types(module).size)
        }
    }

    /** [beam]'s types are read, and each of [expected], a (visibility, name, arity), is among its type stubs. */
    private fun assertTypesRead(ebin: File, beam: String, vararg expected: Triple<Visibility, String, Int>) {
        val built = stub(File(ebin, "$beam.beam"))

        for ((which, root) in listOf("built" to built, "deserialized" to serializedAndBack(built))) {
            val module = module(root)
            val types = types(module).map { Triple(it.visibility, it.name, it.arity) }

            assertTrue("$beam's types read, $which", module.typesRead())
            for ((visibility, name, arity) in expected) {
                assertTrue("$beam's $visibility $name/$arity among its type stubs, $which: $types", Triple(visibility, name, arity) in types)
            }
        }
    }

    private fun stub(beam: File): Stub = BeamFileImpl.buildFileStub(beam.readBytes(), beam.path)!!

    private fun module(root: Stub): ModuleStub<*> = root.childrenStubs.single() as ModuleStub<*>

    private fun types(module: ModuleStub<*>): List<TypeDefinitionStub<*>> = module.childrenStubs.filterIsInstance<TypeDefinitionStub<*>>()

    private companion object {
        /** A protocol with a type of its own beside the default `t`, compiled to disk, consolidated, as its binary. */
        val CONSOLIDATED_PROBE = """
            dir = Path.join(System.tmp_dir!(), "consolidate_probe_" <> Integer.to_string(System.unique_integer([:positive])))
            File.mkdir_p!(dir)
            source = Path.join(dir, "probe.ex")
            File.write!(source, "defprotocol ConsolidateProbe do\n  @type extra :: integer()\n  def f(value)\nend\n")
            {:ok, _, _} = Kernel.ParallelCompiler.compile_to_path([source], dir)
            true = Code.prepend_path(dir)
            {:ok, binary} = Protocol.consolidate(ConsolidateProbe, [])
            Code.delete_path(dir)
            File.rm_rf!(dir)

            IntellijElixir.Quoter.Probe.send(__ENV__, binary)
        """.trimIndent()
        val MODULE_EBIN = File("testData/org/elixir_lang/model/psi/module/ebin")
        val MODULE_BEAMS = File("testData/org/elixir_lang/beam/decompiler")
        val UNREAD_TYPES_EBIN = File("testData/org/elixir_lang/inspection/unresolvable_type/ebin")
        val TYPE_EBIN = File("testData/org/elixir_lang/model/psi/type/ebin")
    }
}
