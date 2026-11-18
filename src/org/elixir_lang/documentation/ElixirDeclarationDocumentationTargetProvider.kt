package org.elixir_lang.documentation

import com.intellij.model.Pointer
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.createSmartPointer
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.declaration.Feature
import org.elixir_lang.declaration.Form
import org.elixir_lang.declaration.sourceFor
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.scope.call_definition_clause.DeclaringForm

/**
 * Documentation at the name a declaration writes, which has no reference to document through, at a use wherever
 * [DocumentedAt.answeredAt] answers, and in a template wherever [DocumentedAt.of] does. It answers nowhere else.
 */
internal class ElixirDeclarationDocumentationTargetProvider : DocumentationTargetProvider {
    // A caret just after a name is on the next leaf, as at the end of `def push<caret>(a)`.
    override fun documentationTargets(file: PsiFile, offset: Int): List<DocumentationTarget> =
        listOfNotNull(targetAt(file, offset) ?: if (offset > 0) targetAt(file, offset - 1) else null)

    private fun targetAt(file: PsiFile, offset: Int): DocumentationTarget? {
        val leaf = file.findElementAt(offset)?.takeIf { it.language.isKindOf(ElixirLanguage) } ?: return null
        val named = DeclaringForm.named(leaf)

        return if (named != null && sourceFor(Feature.DOCUMENTATION).declaredForm(named.call) != null) {
            val documented = if (named.form == Form.DELEGATION) {
                PsiTreeUtil.getParentOfType(leaf, Call::class.java, false)?.let(::delegationHeadDocumented)
            } else {
                named.call
            }

            documented?.let { ElixirDocumentationTarget.of(it, leaf) }
        } else {
            DocumentedAt.answeredAt(leaf)?.let { choice ->
                choice.element?.let { ElixirDocumentationTarget.of(it, leaf) } ?: Undocumented(leaf)
            } ?: inTemplate(file, leaf)
        }
    }

    /**
     * What [leaf] documents in a template, a `.heex` file or a `~H`, `~E` or `~L` sigil, as it does in `.ex`. The platform
     * asks a file's language for the documentation element at the caret, and a template's has no provider, so the
     * element it finds is the call itself, which documents nothing.
     */
    private fun inTemplate(file: PsiFile, leaf: PsiElement): DocumentationTarget? =
        if (file.language.isKindOf(ElixirLanguage)) null else DocumentedAt.of(leaf)?.let { ElixirDocumentationTarget.of(it, leaf) }

    /** A `defdelegate` [head] documents its delegation, then what it delegates to. */
    private fun delegationHeadDocumented(head: Call): PsiElement? {
        val preferred = Documented.headed(head) ?: return null

        return Documented.documented(listOf(preferred.found) + preferred.admittedDelegatedTo)
    }

    /** A use at [leaf] that documents nothing, which the platform shows as no documentation found. */
    @Suppress("UnstableApiUsage")
    private class Undocumented(private val leaf: PsiElement) : DocumentationTarget {
        override fun createPointer(): Pointer<out DocumentationTarget> =
            Pointer.delegatingPointer(leaf.createSmartPointer(), ::Undocumented)

        override fun computePresentation(): TargetPresentation = TargetPresentation.builder(leaf.text).presentation()

        override val navigatable: Navigatable? get() = null
    }
}
