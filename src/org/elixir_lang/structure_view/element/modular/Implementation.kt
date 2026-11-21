package org.elixir_lang.structure_view.element.modular

import com.intellij.navigation.ItemPresentation
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.navigation.item_presentation.Implementation
import org.elixir_lang.psi.Implementation.forText
import org.elixir_lang.psi.Implementation.name as implementationName
import org.elixir_lang.psi.Implementation.protocolName
import org.elixir_lang.psi.NamedElement
import org.elixir_lang.psi.call.Call

class Implementation : Module {
    /**
     * The name of the [.navigationItem].
     *
     * @return the [NamedElement.getName] if [.navigationItem] is a [NamedElement]; otherwise,
     * `null`.
     */
    override fun getName(): String? = if (forNameOverride != null) {
        protocolName(navigationItem)?.let { protocolName -> implementationName(protocolName, forNameOverride) }
    } else if (navigationItem is NamedElement) {
        val namedElement = navigationItem as NamedElement
        namedElement.name
    } else {
        null
    }

    private val forNameOverride: String?

    constructor(call: Call) : this(null, call)
    constructor(parent: Modular?, call: Call) : super(parent, call) {
        forNameOverride = null
    }

    /**
     * Implementation that presents as being for [forNameOverride] alone, one of the modules the `defimpl` is for, so
     * that each module of a `for:` list is its own Go to Class and Go to Symbol entry.
     */
    constructor(parent: Modular?, call: Call, forNameOverride: String) : super(parent, call) {
        this.forNameOverride = forNameOverride
    }

    private fun forName(): String = forNameOverride ?: forText(navigationItem) ?: "?"

    /**
     * Returns the presentation of the tree element.
     *
     * @return the element presentation.
     */
    override fun getPresentation(): ItemPresentation = Implementation(
            protocolName(),
            forName()
    )

    /**
     * Unlike [.protocolName], will return "?" when the protocol name can't be derived from the call.
     */
    @RequiresReadLock
    fun protocolName(): String = protocolName(navigationItem) ?: "?"
}
