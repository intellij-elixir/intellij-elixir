package org.elixir_lang.model.psi

import com.intellij.find.usages.api.PsiUsage
import com.intellij.model.Pointer
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.usages.impl.rules.UsageType

/**
 * A [PsiUsage] backed by a file + range, optionally carrying a [UsageType] so it is grouped under a
 * dedicated heading (e.g. "Implementation") in the usage view. Port of Markdown's `MarkdownPsiUsage`.
 *
 * [usageTextByName] is set when renaming must write something other than the target's new name
 * verbatim into [range] - e.g. a multi-alias group member `Renamee` in `alias Grouped.{Renamee,
 * Sibling}` must be rewritten with the new name RELATIVE to the group qualifier, and a bare
 * aliased reference `Renamee.hello()` with only the new name's last segment. It must be a
 * stateless string transformation (the platform may run it on the UI thread during in-place
 * rename). `null` means the new name is written as-is.
 */
@Suppress("UnstableApiUsage")
class ElixirPsiUsage(
    override val file: PsiFile,
    override val range: TextRange,
    override val declaration: Boolean,
    override val usageType: UsageType? = null,
    val usageTextByName: ((String) -> String)? = null,
    val purpose: Purpose = Purpose.ALL
) : PsiUsage {
    /**
     * Which search a usage serves: a `defdelegate` head is a use of its target a rename leaves alone ([FIND]), and the
     * `as:` that keeps it pointing there is an edit Find Usages does not show ([RENAME]).
     */
    enum class Purpose { ALL, FIND, RENAME }

    /** This usage at the same range of [file], as a decompiled mirror's is re-anchored to its compiled file. */
    fun anchoredIn(file: PsiFile): ElixirPsiUsage = ElixirPsiUsage(file, range, declaration, usageType, usageTextByName, purpose)

    override fun createPointer(): Pointer<out PsiUsage> {
        val declaration = this.declaration // capture for the restore lambda
        val usageType = this.usageType
        val usageTextByName = this.usageTextByName
        val purpose = this.purpose
        return Pointer.fileRangePointer(file, range) { restoredFile, restoredRange ->
            ElixirPsiUsage(restoredFile, restoredRange, declaration, usageType, usageTextByName, purpose)
        }
    }

    companion object {
        fun create(
            element: PsiElement,
            rangeInElement: TextRange,
            declaration: Boolean = false,
            usageType: UsageType? = null,
            usageTextByName: ((String) -> String)? = null,
            purpose: Purpose = Purpose.ALL
        ): ElixirPsiUsage =
            if (element is PsiFile) {
                ElixirPsiUsage(element, rangeInElement, declaration, usageType, usageTextByName, purpose)
            } else {
                ElixirPsiUsage(
                    element.containingFile,
                    rangeInElement.shiftRight(element.textRange.startOffset),
                    declaration,
                    usageType,
                    usageTextByName,
                    purpose
                )
            }
    }
}
