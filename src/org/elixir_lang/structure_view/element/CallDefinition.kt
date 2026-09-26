package org.elixir_lang.structure_view.element

import org.elixir_lang.psi.CallableDeclaration
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.psi.ResolveState
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.call.Visibility
import org.elixir_lang.navigation.item_presentation.NameArity
import org.elixir_lang.navigation.item_presentation.Parent
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.call.Call
import org.elixir_lang.structure_view.element.modular.Modular

/**
 * A definition for a call: either a function or a macro
 * @param modular The scoping module or quote
 */
class CallDefinition(val modular: Modular, private val time: Timed.Time, private val name: String, val arity: Int) :
    StructureViewTreeElement, Timed, Visible, NavigationItem {
    /**
     * * `true` to mark as overridable by another function of the same name and arity;
     * * `false` to make as non-overridable.
     */
    var isOverridable: Boolean = false

    /**
     * @param override `true` to mark as an override of another function; `false` to mark as an independent
     * function
     */
    var override: Boolean = false

    // keeps track of total order of all children (clauses, heads, and specifications)
    private val childList = mutableListOf<TreeElement>()
    private val clauseList = mutableListOf<CallDefinitionClause>()
    private val headList = mutableListOf<CallDefinitionHead>()
    private val eexFunctionFromList = mutableListOf<EExFunctionFrom>()

    private val specificationList = ArrayList<CallDefinitionSpecification>()

    /**
     * All arguments to the CallDefinition constructor
     */
    data class Tuple(val modular: Modular, val time: Timed.Time, val name: String, val arity: Int)

    /**
     * Adds clause to macro
     *
     * @param clause the new clause for the macro
     */
    @RequiresReadLock
    fun clause(clause: Call, definer: CallableDeclaration.Definer): CallDefinitionClause {
        val nameArityInterval =
            org.elixir_lang.psi.CallDefinitionClause.nameArityInterval(clause, ResolveState.initial())!!

        assert(nameArityInterval.name == name)
        assert(arity in nameArityInterval.arityInterval)

        val callDefinitionClause = CallDefinitionClause(this, clause, definer)
        childList.add(callDefinitionClause)
        clauseList.add(callDefinitionClause)

        return callDefinitionClause
    }

    fun clauseList(): List<CallDefinitionClause> {
        return clauseList
    }

    /**
     * Returns the clauses of the macro
     *
     * @return the list of [CallDefinitionClause] elements.
     */
    override fun getChildren(): Array<TreeElement> = childList.toTypedArray()

    /**
     * Returns the data object (usually a PSI element) corresponding to the
     * structure view element.
     *
     * @return the data object instance.
     */
    override fun getValue(): Any? = headList.firstOrNull() ?: clauseList.firstOrNull() ?: eexFunctionFromList.firstOrNull()?.call

    /**
     * A macro groups together one or more [CallDefinitionClause] elements, so it can navigate if it has clauses, or an
     * `EEx.function_from_*` call.
     *
     * @return `true` if [.clauseList] size is greater than 0 or an EEx call declares it; otherwise, `false`.
     */
    override fun canNavigate(): Boolean = clauseList.size > 0 || eexFunctionFromList.isNotEmpty()

    /**
     * A macro groups together one or more [CallDefinitionClause] elements, so it can navigate if it has clauses, or an
     * `EEx.function_from_*` call.
     *
     * @return `true` if [.clauseList] size is greater than 0 or an EEx call declares it; otherwise, `false`.
     */
    override fun canNavigateToSource(): Boolean = canNavigate()

    override fun getName(): String = "$name/$arity"

    /**
     * Returns the presentation of the tree element.
     *
     * @return the element presentation.
     */
    override fun getPresentation(): ItemPresentation =
        (modular.presentation as? Parent)?.locatedPresentableText.let { location ->
            NameArity(
                location,
                false,
                time,
                visibility(),
                isOverridable,
                override,
                name,
                arity
            )
        }

    /** The `EEx.function_from_*` call that declares this function, shown as its head. */
    fun eexFunctionFrom(eexFunctionFrom: EExFunctionFrom) {
        childList.add(EExFunctionFromHead(eexFunctionFrom))
        eexFunctionFromList.add(eexFunctionFrom)
    }

    /**
     * Unlike a clause, a head is just the name and arguments, without the outer macro calls.  Heads occur in
     * `Kernel.defdelegate/2`.
     *
     * @param call
     */
    fun head(head: Call) {
        val nameArityInterval = CallDefinitionHead.nameArityInterval(head, ResolveState.initial())!!

        assert(nameArityInterval.name == name)
        assert(arity in nameArityInterval.arityInterval)

        val callDefinitionHead = CallDefinitionHead(this, Visibility.PUBLIC, head)
        childList.add(callDefinitionHead)
        headList.add(callDefinitionHead)
    }

    fun name(): String = name

    /**
     * Navigates to first clause in [.clauseList].
     *
     * @param requestFocus `true` if focus requesting is necessary
     */
    override fun navigate(requestFocus: Boolean) {
        clauseList.firstOrNull()?.navigate(requestFocus) ?: eexFunctionFromList.firstOrNull()?.navigate(requestFocus)
    }

    /**
     * When the defined call is usable
     *
     * @return [Timed.Time.COMPILE] for compile time (`defmacro`, `defmacrop`);
     * [Timed.Time.RUN] for run time `def`, `defp`)
     */
    override fun time(): Timed.Time = time

    /**
     * @param moduleAttributeDefinition
     */
    fun specification(moduleAttributeDefinition: AtUnqualifiedNoParenthesesCall<*>) {
        val nameArity = CallDefinitionSpecification.moduleAttributeNameArity(
            moduleAttributeDefinition
        )!!

        assert(nameArity.name == name)
        assert(nameArity.arity == arity)

        val callDefinitionSpecification = CallDefinitionSpecification(
            modular,
            moduleAttributeDefinition,
            false,
            Timed.Time.RUN
        )
        childList.add(callDefinitionSpecification)
        specificationList.add(callDefinitionSpecification)
    }

    /**
     * The visibility of the element.
     *
     * @return [Visibility.PUBLIC] for public call definitions (`def` and `defmacro`);
     * [Visibility.PRIVATE] for private call definitions (`defp` and `defmacrop`); `null` for
     * a mix of visibilities, such as when a call definition has a mix of call definition clause visibilities, which
     * is invalid code, but can occur temporarily while code is being edited.
     */
    override fun visibility(): Visibility? {
        var privateCount = 0
        var publicCount = 0

        for (callDefinitionHead in headList) {
            when (callDefinitionHead.visibility()) {
                Visibility.PRIVATE -> privateCount++
                Visibility.PUBLIC -> publicCount++
            }
        }

        for (callDefinitionClause in clauseList) {
            when (callDefinitionClause.visibility()) {
                Visibility.PRIVATE -> privateCount++
                Visibility.PUBLIC -> publicCount++
            }
        }

        for (eexFunctionFrom in eexFunctionFromList) {
            when (eexFunctionFrom.visibility()) {
                Visibility.PRIVATE -> privateCount++
                Visibility.PUBLIC -> publicCount++
                null -> Unit
            }
        }

        return if (privateCount > 0 && publicCount == 0) {
            Visibility.PRIVATE
        } else if (privateCount == 0 && publicCount > 0) {
            Visibility.PUBLIC
        } else {
            null
        }
    }

    companion object {
        /**
         * @param call a `def*` call
         * @param definer [call]'s `def*`
         */
        @RequiresReadLock
        fun fromCall(call: Call, definer: CallableDeclaration.Definer): CallDefinition? =
            CallDefinitionClause.enclosingModular(call)?.let { modular ->
                org.elixir_lang.psi.CallDefinitionClause.nameArityInterval(call, ResolveState.initial())
                    ?.let { nameArityInterval ->
                        val name = nameArityInterval.name
                        /* arity is the function's, the most arguments, because that's how {@code h} and
                           ExDoc treat functions with defaults. */
                        val arity = nameArityInterval.arityInterval.let { it.functionArity ?: it.minimum }
                        val time = CallDefinitionClause.time(definer)

                        CallDefinition(modular, time, name, arity)
                    }
            }
    }

}
