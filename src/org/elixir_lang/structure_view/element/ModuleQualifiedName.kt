package org.elixir_lang.structure_view.element

import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.Implementation
import org.elixir_lang.psi.Module
import org.elixir_lang.psi.Protocol
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.QuoteMacro

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
            enclosing(call)?.qualifiedName() ?: ModuleQualifiedName(null, fallbackName)

        /** A `quote`'s module is the one it is injected into, which it does not name. */
        private fun enclosing(call: Call): Call? =
            CallDefinitionClause.enclosingModularMacroCall(call)?.takeUnless { QuoteMacro.`is`(it) }

        private fun Call.qualifiedName(): ModuleQualifiedName? {
            if (Implementation.`is`(this)) {
                implementationQualifiedName()?.let { qualifiedName -> return qualifiedName }
            }

            // `defprotocol` defines a module the same way, and takes its name in the same argument.
            if (Module.`is`(this) || Protocol.`is`(this)) {
                val name = Module.name(this)

                return split(enclosing(this)?.qualifiedName()?.let { outer -> "${outer.full}.$name" } ?: name)
            }

            return enclosing(this)?.qualifiedName()
        }

        /** `defimpl P, for: T` compiles onto `P.T`, whatever module it is written in. */
        private fun Call.implementationQualifiedName(): ModuleQualifiedName? {
            val protocolName = Implementation.protocolName(this) ?: return null
            val forNames = Implementation.forNameCollection(null, this)

            // A `for:` list compiles to one module per entry and the tree builds a single node for all
            // of them, so it is named after the whole list.
            if (forNames != null && forNames.size != 1) {
                return ModuleQualifiedName(protocolName, forNames.joinToString(", ", "[", "]"))
            }

            // `defimpl P do` with no `for:` means the module it is written in.
            val forName = forNames?.singleOrNull() ?: enclosing(this)?.qualifiedName()?.full ?: return null

            return split("$protocolName.$forName")
        }

        private fun split(moduleName: String): ModuleQualifiedName {
            val lastIndex = moduleName.lastIndexOf('.')

            return if (lastIndex != -1) {
                ModuleQualifiedName(moduleName.substring(0, lastIndex), moduleName.substring(lastIndex + 1))
            } else {
                ModuleQualifiedName(null, moduleName)
            }
        }
    }
}
