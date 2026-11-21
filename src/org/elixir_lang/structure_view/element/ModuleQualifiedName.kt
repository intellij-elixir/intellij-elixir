package org.elixir_lang.structure_view.element

import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.Module.inspect
import org.elixir_lang.Module.nest
import org.elixir_lang.Module.nonElixirAtom
import org.elixir_lang.psi.Implementation
import org.elixir_lang.psi.Module
import org.elixir_lang.psi.Protocol
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.enclosingMacroCall

/**
 * The qualifier and final segment of the module a node is compiled onto, which is how
 * `item_presentation` renders a struct or an exception.
 */
data class ModuleQualifiedName(val location: String?, val name: String) {
    private val full: String get() = location?.let { location -> "$location.$name" } ?: name

    companion object {
        /** @param fallbackName for a call that is in no module, and so has no name to take. */
        @JvmStatic
        @RequiresReadLock
        fun of(call: Call, fallbackName: String): ModuleQualifiedName =
            call.enclosingMacroCall()?.qualifiedName()?.inspected() ?: ModuleQualifiedName(null, fallbackName)

        /** An atom-named module, which [split] leaves whole, is shown as Elixir shows it. */
        private fun ModuleQualifiedName.inspected(): ModuleQualifiedName =
            if (location == null && nonElixirAtom(name) != null) copy(name = inspect(name)) else this

        private fun Call.qualifiedName(): ModuleQualifiedName? {
            if (Implementation.`is`(this)) {
                implementationQualifiedName()?.let { qualifiedName -> return qualifiedName }
            }

            // `defprotocol` defines a module the same way, and takes its name in the same argument.
            if (Module.`is`(this) || Protocol.`is`(this)) {
                val name = Module.name(this)

                return split(enclosingMacroCall()?.qualifiedName()?.let { outer -> nest(outer.full, name) } ?: name)
            }

            return enclosingMacroCall()?.qualifiedName()
        }

        /** `defimpl P, for: T` compiles onto `P.T`, whatever module it is written in. */
        private fun Call.implementationQualifiedName(): ModuleQualifiedName? {
            val protocolName = Implementation.protocolName(this) ?: return null
            val forNames = Implementation.forNames(this) ?: return null

            // A `for:` list compiles to one module per entry and the tree builds a single node for all
            // of them, so it is named after the whole list.
            return forNames.singleOrNull()?.let { forName -> split(Implementation.name(protocolName, forName)) }
                ?: ModuleQualifiedName(protocolName, Implementation.forText(forNames))
        }

        /** An atom-named module, such as `:"Elixir.P.foo"`, is one atom with no qualifier. */
        private fun split(moduleName: String): ModuleQualifiedName {
            val lastIndex = moduleName.lastIndexOf('.')

            return if (lastIndex != -1 && nonElixirAtom(moduleName) == null) {
                ModuleQualifiedName(moduleName.substring(0, lastIndex), moduleName.substring(lastIndex + 1))
            } else {
                ModuleQualifiedName(null, moduleName)
            }
        }
    }
}
