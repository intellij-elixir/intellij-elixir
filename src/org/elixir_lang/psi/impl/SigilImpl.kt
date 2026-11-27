package org.elixir_lang.psi.impl

object SigilImpl {
    @JvmStatic
    fun sigilDelimiter(sigil: org.elixir_lang.psi.Sigil): String = sigil.node.getChildren(null)[2].text

    @JvmStatic
    fun sigilName(sigil: org.elixir_lang.psi.Sigil): String = sigil.node.firstChildNode.treeNext.text
}
