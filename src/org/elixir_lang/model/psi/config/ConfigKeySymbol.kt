package org.elixir_lang.model.psi.config

import com.intellij.find.usages.api.SearchTarget
import com.intellij.find.usages.api.UsageHandler
import com.intellij.model.Pointer
import com.intellij.navigation.NavigatableSymbol
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.platform.backend.navigation.NavigationRequest
import com.intellij.platform.backend.navigation.NavigationTarget
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiFile
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.code.InspectAtom
import java.util.Objects

/** [app]'s first-level application env [key], both atoms. Go To lands on each config line that sets it. */
@Suppress("UnstableApiUsage")
data class ConfigKeySymbol(val app: String, val key: String) : NavigatableSymbol, SearchTarget {
    override fun createPointer(): Pointer<ConfigKeySymbol> = Pointer.hardPointer(this)

    @RequiresReadLock
    override fun getNavigationTargets(project: Project): Collection<NavigationTarget> =
        ConfigSettings.of(project, app, key).map { Setting(it.containingFile, it.textRange) }

    override val usageHandler: UsageHandler get() = UsageHandler.createEmptyUsageHandler(InspectAtom.literal(key))

    override fun presentation(): TargetPresentation =
        TargetPresentation.builder(InspectAtom.literal(key)).containerText(InspectAtom.literal(app)).presentation()

    /** A config line setting the key, labelled by its file. */
    private class Setting(private val file: PsiFile, private val range: TextRange) : NavigationTarget {
        override fun createPointer(): Pointer<out NavigationTarget> = Pointer.fileRangePointer(file, range, ::Setting)

        override fun computePresentation(): TargetPresentation {
            val document = file.viewProvider.document
            val line = document.getLineNumber(range.startOffset)
            val text = document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line))).trim()

            return TargetPresentation.builder(text).locationText(file.name).presentation()
        }

        override fun navigationRequest(): NavigationRequest? = NavigationRequest.sourceNavigationRequest(file, range)

        override fun equals(other: Any?): Boolean = other is Setting && other.file == file && other.range == range

        override fun hashCode(): Int = Objects.hash(file, range)
    }
}
