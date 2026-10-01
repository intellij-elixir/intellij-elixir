package org.elixir_lang.psi.__module__

import com.intellij.navigation.ItemPresentation
import com.intellij.psi.PsiNamedElement
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.locationString
import org.elixir_lang.reference.module.UnaliasedName
import org.elixir_lang.util.ReadActions
import javax.swing.Icon

class AliasPresentation(private val __MODULE__Call: Call) : ItemPresentation {
    override fun getIcon(unused: Boolean): Icon? = null
    override fun getLocationString(): String = _locationString
    override fun getPresentableText(): String = _presentableText

    private val _locationString by lazy {
        __MODULE__Call.containingFile.locationString(__MODULE__Call.project)
    }

    private val _presentableText by lazy(LazyThreadSafetyMode.PUBLICATION) {
        ReadActions.compute {
            UnaliasedName.unaliasedName(__MODULE__Call as PsiNamedElement)?.let { "alias $it" } ?: __MODULE__Call.text
        }
    }
}
