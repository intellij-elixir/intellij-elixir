package org.elixir_lang.beam.psi.stubs

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.stubs.IndexSink
import com.intellij.psi.stubs.StubElement
import com.intellij.psi.stubs.StubInputStream
import com.intellij.psi.stubs.StubOutputStream
import org.elixir_lang.beam.psi.Module
import org.elixir_lang.beam.psi.ModuleElement
import org.elixir_lang.beam.psi.impl.ModuleImpl
import org.elixir_lang.beam.psi.impl.ModuleStubImpl
import org.elixir_lang.psi.stub.call.Deserialized
import org.elixir_lang.psi.stub.type.Named.Companion.indexStubbic
import java.io.IOException

class ModuleType(debugName: String) : ModuleElementType<ModuleStub<*>, Module>(debugName) {
    override fun createCompositeNode(): ASTNode = ModuleElement(this)

    override fun createPsi(stub: ModuleStub<*>): Module = ModuleImpl(stub)

    override fun createStub(psi: Module, parentStub: StubElement<out PsiElement>): ModuleStub<*> =
        super.createStub(psi, parentStub)

    @Throws(IOException::class)
    override fun serialize(stub: ModuleStub<*>, stubOutputStream: StubOutputStream) {
        Deserialized.serialize(stubOutputStream, stub)
        stubOutputStream.writeBoolean(stub.typesRead())
    }

    @Throws(IOException::class)
    override fun deserialize(stubInputStream: StubInputStream, parentStub: StubElement<*>): ModuleStub<*> {
        val deserialized = Deserialized.deserialize(stubInputStream)
        return ModuleStubImpl<Module>(parentStub, deserialized, stubInputStream.readBoolean())
    }

    override fun indexStub(stub: ModuleStub<*>, sink: IndexSink) {
        indexStubbic(stub, sink)
    }
}
