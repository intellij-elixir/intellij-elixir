package org.elixir_lang.structure_view.element

import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.navigation.ItemPresentation
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.call.Visibility
import org.elixir_lang.declaration.Capabilities
import org.elixir_lang.errorreport.Logger
import org.elixir_lang.navigation.item_presentation.NameArity
import org.elixir_lang.psi.CallDefinitionClause.capabilities
import org.elixir_lang.psi.CallDefinitionClause.enclosingModularMacroCall
import org.elixir_lang.psi.CallDefinitionClause.head
import org.elixir_lang.psi.QuoteMacro
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.enclosingMacroCall
import org.elixir_lang.structure_view.element.modular.*
import org.jetbrains.annotations.Contract

/**
 * Constructs a clause for `callDefinition`.
 *
 * @param callDefinition holds all sibling clauses for `call` for the same name, arity. and time
 * @param call           a def(macro)?p? call
 */
class CallDefinitionClause(val callDefinition: CallDefinition, call: Call) :
    Element<Call>(call), Presentable, Visible {
    private val visibility: Visibility = visibility(call)

    /*
     * Public Instance Methods
     */

    override fun getChildren(): Array<TreeElement> =
        Body.treeElements(callDefinition.modular, { Quote(this, it) }, navigationItem)

    /**
     * Returns the presentation of the tree element.
     *
     * @return the element presentation.
     */
    override fun getPresentation(): ItemPresentation =
        org.elixir_lang.navigation.item_presentation.CallDefinitionHead(
            callDefinition.presentation as NameArity,
            visibility(),
            head(navigationItem)!!
        )

    /**
     * The visibility of the element.
     *
     * @return `Visible.Visibility.PUBLIC` for public call definitions (`def` and `defmacro`);
     * `Visible.Visibility.PRIVATE` for private call definitions (`defp` and `defmacrop`).
     */
    override fun visibility(): Visibility = visibility

    companion object {
        /**
         * The module or `quote` that encapsulates `call`
         *
         * @param call a def(macro)?p?
         * @return `null` if gets to the enclosing file without finding a quote or module
         */
        @RequiresReadLock
        @Contract(pure = true)
        fun enclosingModular(call: Call): Modular? =
            enclosingModularMacroCall(call)?.let {
                modular(it)
            }

        @RequiresReadLock
        @Contract(pure = true)
        fun modular(enclosingMacroCall: Call): Modular? {
            var modular: Modular? = null

            // All classes under {@link org.elixir_lang.structure_view.element.Modular}
            if (org.elixir_lang.psi.Implementation.`is`(enclosingMacroCall)) {
                val grandScope = enclosingModular(enclosingMacroCall)
                modular = Implementation(grandScope, enclosingMacroCall)
            } else if (org.elixir_lang.psi.Module.`is`(enclosingMacroCall)) {
                val grandScope = enclosingModular(enclosingMacroCall)
                modular = Module(grandScope, enclosingMacroCall)
            } else if (org.elixir_lang.psi.Protocol.`is`(enclosingMacroCall)) {
                val grandScope = enclosingModular(enclosingMacroCall)
                modular = Protocol(grandScope, enclosingMacroCall)
            } else if (QuoteMacro.`is`(enclosingMacroCall)) {
                val quoteEnclosingMacroCall = enclosingMacroCall.enclosingMacroCall()
                var quote: Quote? = null

                if (quoteEnclosingMacroCall == null) {
                    quote = Quote(enclosingMacroCall)
                } else if (org.elixir_lang.psi.CallDefinitionClause.`is`(quoteEnclosingMacroCall)) {
                    val callDefinitionClause = CallDefinitionClause.fromCall(quoteEnclosingMacroCall)

                    // A headless `defmacro` has no clause to hang the quote on yet
                    if (callDefinitionClause != null) {
                        quote = Quote(
                            callDefinitionClause,
                            enclosingMacroCall
                        )
                    }
                } else {
                    quote = Quote(modular(quoteEnclosingMacroCall), enclosingMacroCall)
                }

                if (quote != null) {
                    modular = quote.modular()
                }
            } else if (Unknown.`is`(enclosingMacroCall)) {
                val grandScope = enclosingModular(enclosingMacroCall)
                modular = Unknown(grandScope, enclosingMacroCall)
            }

            return modular
        }

        /**
         * Whether the `call` is defining something for runtime, like a function, or something for compile time, like
         * a macro.
         *
         * @param call def(macro)?p?
         * @return [Timed.Time.COMPILE] for `defmacrop?` and `defguardp?`; [Timed.Time.RUN] for `defp?`
         */
        @RequiresReadLock
        fun time(call: Call): Timed.Time =
            capabilities(call)?.let { time(it) } ?: run {
                Logger.error(logger, "Don't know whether call is at runtime or compile-time", call)

                Timed.Time.RUN
            }

        /** [Timed.Time.COMPILE] for what is expanded at compile time, a guard as much as a macro. */
        @JvmStatic
        fun time(capabilities: Capabilities): Timed.Time =
            if (capabilities.compileTime) Timed.Time.COMPILE else Timed.Time.RUN

        /**
         * @param call
         * @return `Visible.Visibility.PUBLIC` for `def` or `defmacro`; `Visible.Visibility.PRIVATE`
         * for `defp` and `defmacrop`; `null` only if `call` is unrecognized
         */
        @RequiresReadLock
        fun visibility(call: Call): Visibility =
            capabilities(call)?.visibility ?: run {
                Logger.error(logger, "Don't know whether call is public or private", call)

                Visibility.PUBLIC
            }

        /**
         * Constructs [.callDefinition] from `code`, such as when showing structure in Go To Symbol
         *
         * @param call a def(macro)?p? call
         */
        @RequiresReadLock
        fun fromCall(call: Call): CallDefinitionClause? =
            CallDefinition.fromCall(call)?.let {
                CallDefinitionClause(it, call)
            }

        private val logger by lazy { com.intellij.openapi.diagnostic.Logger.getInstance(CallDefinitionClause::class.java) }
    }
}
