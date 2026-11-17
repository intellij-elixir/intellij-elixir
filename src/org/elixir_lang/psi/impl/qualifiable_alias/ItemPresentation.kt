package org.elixir_lang.psi.impl.qualifiable_alias

import com.intellij.navigation.ItemPresentation
import com.intellij.psi.PsiNamedElement
import org.elixir_lang.psi.*
import org.elixir_lang.psi.impl.locationString
import org.elixir_lang.reference.module.UnaliasedName
import org.elixir_lang.util.ReadActions
import javax.swing.Icon

class ItemPresentation(private val qualifiableAlias: QualifiableAlias) : ItemPresentation {
    override fun getLocationString(): String = _locationString
    override fun getIcon(unused: Boolean): Icon? = null
    override fun getPresentableText(): String? = _presentableText

    private val _locationString by lazy {
        qualifiableAlias.containingFile.locationString(qualifiableAlias.project)
    }

    private val _presentableText by lazy(LazyThreadSafetyMode.PUBLICATION) {
        ReadActions.compute {
            if (qualifiableAlias.parent is ElixirAccessExpression) {
                aliasCallPresentableText()
            } else {
                qualifiableAlias.fullyQualifiedName()
            }
        }
    }

    private fun aliasCallPresentableText(): String? =
            when (val position = Alias.position(qualifiableAlias)) {
                is Alias.Position.InFirstArgument ->
                    UnaliasedName.unaliasedName(qualifiableAlias)?.let { "alias $it" } ?: qualifiableAlias.fullyQualifiedName()

                is Alias.Position.InAsValue ->
                    if (position.asValue === qualifiableAlias) {
                        (position.firstArgument as? PsiNamedElement)
                            ?.let { UnaliasedName.unaliasedName(it) }
                            ?.let { "alias $it, as: ${qualifiableAlias.name}" }
                            ?: qualifiableAlias.name
                    } else {
                        qualifiableAlias.fullyQualifiedName()
                    }

                is Alias.Position.Elsewhere ->
                    null

                null ->
                    qualifiableAlias.fullyQualifiedName()
            }
}
