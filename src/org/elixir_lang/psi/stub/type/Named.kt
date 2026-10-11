package org.elixir_lang.psi.stub.type

import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.stubs.IndexSink
import com.intellij.psi.stubs.NamedStubBase
import org.elixir_lang.Module
import org.elixir_lang.psi.Definition
import org.elixir_lang.psi.NamedElement
import org.elixir_lang.psi.stub.call.Stubbic
import org.elixir_lang.psi.stub.index.*
import org.jetbrains.annotations.NonNls

abstract class Named<S : NamedStubBase<T>, T : PsiNameIdentifierOwner>(@NonNls debugName: String) : Element<S, T>(debugName) {
    override fun indexStub(stub: S, sink: IndexSink) {
        indexStubbic(stub as Stubbic, sink)
    }

    override fun getExternalId(): String = "elixir." + super.toString()

    companion object {
        /**
         * The names a call defining [definition] with [nameSet], its stub's canonical names, is indexed under. Stub-safe:
         * nothing it calls may resolve or expand.
         */
        @JvmStatic
        fun indexedNames(definition: Definition?, nameSet: Set<String>): Collection<String> =
            // A module named only at run time has no name a use could write.
            if (definition?.type == Definition.Type.MODULAR) nameSet.filter { Module.atom(it) != null } else nameSet

        @JvmStatic
        fun <T : Stubbic> indexStubbic(stubbic: T, sink: IndexSink) {
            val nameSet = stubbic.canonicalNameSet()
            val definition = stubbic.definition
            val nameKeys = indexedNames(definition, nameSet)

            nameKeys.forEach { name ->
                sink.occurrence<NamedElement, String>(AllName.KEY, name)
            }

            if (definition != null) {
                if (definition.type == Definition.Type.MODULAR) {
                    nameKeys.forEach { name ->
                        sink.occurrence<NamedElement, String>(ModularName.KEY, name)
                    }

                    if (definition == Definition.IMPLEMENTATION) {
                        stubbic.implementedProtocolName?.let { implementedProtocolName ->
                            sink.occurrence<NamedElement, String>(ImplementedProtocolName.KEY, implementedProtocolName)
                        }
                    }
                } else if (definition == Definition.MODULE_ATTRIBUTE) {
                    nameSet.forEach { name ->
                        sink.occurrence<NamedElement, String>(QuoteModuleAttributeName.KEY,  name)
                    }
                } else if (definition == Definition.VARIABLE) {
                    nameSet.forEach { name ->
                        sink.occurrence<NamedElement, String>(QuoteVariableName.KEY, name)
                    }
                }
            }
        }
    }
}
