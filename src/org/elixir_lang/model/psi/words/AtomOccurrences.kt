package org.elixir_lang.model.psi.words

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.injection.sigilHoldsElixir
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.lowering.AtomName
import org.elixir_lang.psi.ElixirTypes
import org.elixir_lang.psi.Sigil

/**
 * Every name [root] spells, each as [AtomName] gives it, so a use is found by the atom it quotes to however it is
 * written. Walks the AST, so PSI is made only for the nodes [AtomName] names.
 */
@RequiresReadLock
internal fun atomOccurrences(root: PsiElement, consumer: (AtomName.Occurrence) -> Unit) {
    val languageLevel = ElixirLanguageLevelResolver.languageLevelFor(root)

    walk(root.node) { node ->
        if (node.elementType in OCCURRENCES) {
            AtomName.occurrence(node.psi, languageLevel)?.let(consumer)
        }
    }
}

/** The sigils under [root] that the injector fills with a language holding Elixir (`~H`, `~E`, `~L`). */
@RequiresReadLock
internal fun templateSigils(root: PsiElement): List<Sigil> {
    val sigils = mutableListOf<Sigil>()

    walk(root.node) { node ->
        if (node.elementType in SIGILS) {
            (node.psi as? Sigil)?.takeIf { sigilHoldsElixir(it.sigilName()) }?.let(sigils::add)
        }
    }

    return sigils
}

/** `true`, `false` and `nil` are words a literal search always finds. */
private val OCCURRENCES = TokenSet.andNot(AtomName.NAMED, TokenSet.create(ElixirTypes.ATOM_KEYWORD))

private val SIGILS = TokenSet.create(ElixirTypes.LITERAL_SIGIL_LINE, ElixirTypes.LITERAL_SIGIL_HEREDOC)

private inline fun walk(root: ASTNode, visit: (ASTNode) -> Unit) {
    var node: ASTNode? = root

    while (node != null) {
        visit(node)
        node = node.firstChildNode ?: following(node, root)
    }
}

/** The node after [node]'s subtree in a pre-order walk of [root]. */
private fun following(node: ASTNode, root: ASTNode): ASTNode? {
    var current = node

    while (current !== root) {
        current.treeNext?.let { return it }
        current = current.treeParent ?: return null
    }

    return null
}
