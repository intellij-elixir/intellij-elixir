package org.elixir_lang.documentation

import com.intellij.codeInsight.navigation.fileLocation
import com.intellij.codeInsight.navigation.fileStatusAttributes
import com.intellij.model.Pointer
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.util.Iconable
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.vfs.newvfs.VfsPresentationUtil
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.pom.Navigatable
import com.intellij.psi.ElementDescriptionUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.createSmartPointer
import com.intellij.usageView.UsageViewShortNameLocation
import com.intellij.usageView.UsageViewTypeLocation
import org.elixir_lang.ElixirLanguage

/**
 * What Quick Documentation, Ctrl+hover and a followed link show for [targetElement], used at [sourceElement]; the
 * documentation opens at [anchor] when it has one.
 */
@Suppress("UnstableApiUsage")
internal class ElixirDocumentationTarget private constructor(
    val targetElement: PsiElement,
    private val sourceElement: PsiElement?,
    private val anchor: String?
) : DocumentationTarget {
    override fun createPointer(): Pointer<out DocumentationTarget> {
        val target = targetElement.createSmartPointer()
        val source = sourceElement?.createSmartPointer()
        // Read here: inside the lambda `anchor` would capture this target, and its PSI with it.
        val anchor = anchor

        return Pointer<ElixirDocumentationTarget> {
            target.element?.let { targetElement ->
                if (source == null) {
                    ElixirDocumentationTarget(targetElement, null, anchor)
                } else {
                    source.element?.let { ElixirDocumentationTarget(targetElement, it, anchor) }
                }
            }
        }
    }

    override fun computePresentation(): TargetPresentation {
        val project = targetElement.project
        val file = targetElement.containingFile?.virtualFile
        val itemPresentation = (targetElement as? NavigationItem)?.presentation
        val location = file?.let { fileLocation(project, it) }

        return TargetPresentation
            .builder(
                itemPresentation?.presentableText
                    ?: (targetElement as? PsiNamedElement)?.name
                    ?: targetElement.text
                    ?: targetElement.toString()
            )
            .backgroundColor(file?.let { VfsPresentationUtil.getFileBackgroundColor(project, it) })
            .icon(targetElement.getIcon(Iconable.ICON_FLAG_VISIBILITY or Iconable.ICON_FLAG_READ_STATUS))
            .containerText(itemPresentation?.locationString, file?.let { fileStatusAttributes(project, it) })
            .locationText(location?.text, location?.icon)
            .presentation()
    }

    override val navigatable: Navigatable? get() = targetElement as? Navigatable

    /** Describes the element, unless the caret is on it. */
    override fun computeDocumentationHint(): @NlsSafe String? =
        if (targetElement !== sourceElement && targetElement !== sourceElement?.parent) {
            hint(targetElement)
        } else {
            null
        }

    override fun computeDocumentation(): DocumentationResult? =
        ElixirDocumentationProvider()
            .generateDoc(targetElement, sourceElement)
            ?.let { DocumentationResult.documentation(it).anchor(anchor) }

    private fun hint(element: PsiElement): String? {
        val name = ElementDescriptionUtil.getElementDescription(element, UsageViewShortNameLocation.INSTANCE)

        if (name.isNotEmpty()) {
            val type = ElementDescriptionUtil.getElementDescription(element, UsageViewTypeLocation.INSTANCE)
            val file = element.containingFile

            return buildString {
                if (type.isNotEmpty()) {
                    append(type).append(' ')
                }

                append('"').append(name).append('"')

                if (file != null && file.isPhysical) {
                    append(" [").append(file.name).append(']')
                }
            }
        }

        return (element as? NavigationItem)?.presentation?.presentableText
    }

    companion object {
        /**
         * The target for [element], or `null` for a file or folder, whose documentation the platform's target
         * completes with the file's details, and for anything not written in Elixir.
         */
        fun of(element: PsiElement, sourceElement: PsiElement?, anchor: String? = null): ElixirDocumentationTarget? =
            if (element !is PsiFileSystemItem && element.language.isKindOf(ElixirLanguage)) {
                ElixirDocumentationTarget(element, sourceElement, anchor)
            } else {
                null
            }
    }
}
