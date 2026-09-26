package org.elixir_lang.structure_view.element

import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.navigation.ItemPresentation
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.call.Visibility
import org.elixir_lang.navigation.item_presentation.NameArity
import org.elixir_lang.psi.CallDefinitionClause.enclosingModularMacroCall
import org.elixir_lang.psi.CallDefinitionClause.head
import org.elixir_lang.psi.CallableDeclaration
import org.elixir_lang.psi.QuoteMacro
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.enclosingMacroCall
import org.elixir_lang.structure_view.element.modular.*
import org.jetbrains.annotations.Contract

/**
 * Constructs a clause for `callDefinition`.
 *
 * @param callDefinition holds all sibling clauses for `call` for the same name, arity. and time
 * @param call           a `def*` call
 * @param definer        [call]'s `def*`, which only a clause has
 */
class CallDefinitionClause(val callDefinition: CallDefinition, call: Call, definer: CallableDeclaration.Definer) :
    Element<Call>(call), Presentable, Visible {
    private val visibility: Visibility = definer.visibility

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

        /** [Timed.Time.COMPILE] for what is expanded at compile time - a macro or a guard - else [Timed.Time.RUN]. */
        fun time(definer: CallableDeclaration.Definer): Timed.Time =
            if (definer.capabilities.compileTime) Timed.Time.COMPILE else Timed.Time.RUN

        /**
         * Constructs [.callDefinition] from `code`, such as when showing structure in Go To Symbol
         *
         * @param call a `def*` call; anything else builds nothing
         */
        @RequiresReadLock
        fun fromCall(call: Call): CallDefinitionClause? =
            CallableDeclaration.definerOf(call)?.let { definer ->
                CallDefinition.fromCall(call, definer)?.let { CallDefinitionClause(it, call, definer) }
            }
    }
}
