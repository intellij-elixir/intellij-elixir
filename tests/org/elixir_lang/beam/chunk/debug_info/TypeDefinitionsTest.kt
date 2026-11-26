package org.elixir_lang.beam.chunk.debug_info

import com.intellij.psi.stubs.Stub
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.beam.psi.BeamFileImpl
import org.elixir_lang.beam.psi.impl.ModuleImpl
import org.elixir_lang.beam.psi.impl.ModuleStubImpl
import org.elixir_lang.beam.psi.stubs.ModuleStub
import org.elixir_lang.beam.psi.stubs.TypeDefinitionStub
import org.elixir_lang.beam.psi.stubs.serializedAndBack
import org.elixir_lang.psi.stub.impl.ElixirFileStubImpl
import java.io.File

/** Whether a `.beam`'s module stub says its types were read, as built and as read back from the index. */
class TypeDefinitionsTest : PlatformTestCase() {
    fun testAnElixirCompiledModulesTypesAreNotRead() = assertTypes(MODULE_EBIN, "Elixir.Code", read = false, count = 0)

    fun testAProtocolsTypesAreNotRead() = assertTypes(MODULE_EBIN, "Elixir.Inspect", read = false, count = 0)

    fun testAnErlangModulesTypesAreReadFromItsAbstractCode() = assertTypes(TYPE_EBIN, "queue", read = true, count = 2)

    /** `erlc` without `debug_info` writes `none` for the abstract code, so the types it defines aren't known. */
    fun testAnErlangModuleCompiledWithoutDebugInfosTypesAreNotRead() =
        assertTypes(UNREAD_TYPES_EBIN, "no_debug_info", read = false, count = 0)

    /** `erlc` compiling Core Erlang writes no forms for the abstract code, so the types it defines aren't known. */
    fun testAnErlangModuleCompiledFromCoreErlangsTypesAreNotRead() =
        assertTypes(UNREAD_TYPES_EBIN, "from_core", read = false, count = 0)

    fun testErlangsBuiltinTypesAreRead() {
        val module = module(stub(File(TYPE_EBIN, "erlang.beam")))

        assertTrue("erlang.beam's types are read", module.typesRead())
        assertNotEmpty(types(module))
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

    private fun stub(beam: File): Stub = BeamFileImpl.buildFileStub(beam.readBytes(), beam.path)!!

    private fun module(root: Stub): ModuleStub<*> = root.childrenStubs.single() as ModuleStub<*>

    private fun types(module: ModuleStub<*>): List<TypeDefinitionStub<*>> = module.childrenStubs.filterIsInstance<TypeDefinitionStub<*>>()

    private companion object {
        val MODULE_EBIN = File("testData/org/elixir_lang/model/psi/module/ebin")
        val UNREAD_TYPES_EBIN = File("testData/org/elixir_lang/inspection/unresolvable_type/ebin")
        val TYPE_EBIN = File("testData/org/elixir_lang/model/psi/type/ebin")
    }
}
