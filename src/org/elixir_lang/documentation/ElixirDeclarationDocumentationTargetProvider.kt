package org.elixir_lang.documentation

import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.declaration.Feature
import org.elixir_lang.declaration.Form
import org.elixir_lang.declaration.Use
import org.elixir_lang.declaration.atItsHead
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.scope.call_definition_clause.DeclaringForm

/**
 * Documentation at the name a declaration writes, which has no reference to document through. It answers nowhere else,
 * so the platform's PSI chain runs as before.
 */
internal class ElixirDeclarationDocumentationTargetProvider : DocumentationTargetProvider {
    // A caret just after a name is on the next leaf, as at the end of `def push<caret>(a)`.
    override fun documentationTargets(file: PsiFile, offset: Int): List<DocumentationTarget> =
        listOfNotNull(targetAt(file, offset) ?: if (offset > 0) targetAt(file, offset - 1) else null)

    private fun targetAt(file: PsiFile, offset: Int): DocumentationTarget? =
        file.findElementAt(offset)?.let { leaf -> documented(leaf)?.let { ElixirDocumentationTarget.of(it, leaf) } }

    private fun documented(leaf: PsiElement): PsiElement? {
        if (!leaf.language.isKindOf(ElixirLanguage)) return null

        val named = DeclaringForm.named(leaf) ?: return null

        // `named` reads shape alone: a call only shaped like `function_from_*` or `embed_*` declares nothing.
        return if (DeclaringForm.declaredBy(named.call) == null) {
            null
        } else if (named.form == Form.DELEGATION) {
            PsiTreeUtil.getParentOfType(leaf, Call::class.java, false)?.let(::delegationHeadDocumented)
        } else {
            named.call
        }
    }

    /** A `defdelegate` [head] documents its delegation, then what it delegates to. */
    private fun delegationHeadDocumented(head: Call): PsiElement? {
        val delegation = DeclaringForm.named(head)?.call ?: return null
        val use = Use.of(head) as? Use.Named ?: return null
        val preferred = atItsHead(delegation, use, Feature.DOCUMENTATION) ?: return null

        return Documented.documented(listOf(preferred.found) + preferred.admittedDelegatedTo)
    }
}
