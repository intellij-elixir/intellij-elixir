package org.elixir_lang.model.psi.generic_server

import com.intellij.icons.AllIcons
import com.intellij.model.Pointer
import com.intellij.model.Symbol
import com.intellij.openapi.util.TextRange
import com.intellij.platform.backend.navigation.NavigationRequest
import com.intellij.platform.backend.navigation.NavigationTarget
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import org.elixir_lang.model.psi.function.UseSymbols
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.call.Call
import java.util.Objects

/**
 * Navigation-only symbol representing a single GenServer message-handler clause
 * (`handle_call/3`, `handle_cast/2` or `handle_info/2`) whose first-parameter pattern matches the
 * message atom at a send site (`GenServer.call/2`, `GenServer.cast/2`, `Process.send/3`,
 * `Process.send_after/3`).
 *
 * Deliberately implements only [Symbol] and [NavigationTarget] - it is neither a `SearchTarget` nor a
 * `RenameTarget`, so it supports forward Ctrl+Click navigation (send site -> handler clause) without
 * participating in Find-Usages or rename.
 *
 * NOTE: [equals]/[hashCode] are by `(file, range)` identity so duplicate matches for the same clause
 * collapse.
 */
@Suppress("UnstableApiUsage")
class GenServerHandlerTarget(
    internal val file: PsiFile,
    internal val range: TextRange,
    private val presentationText: String
) : Symbol, NavigationTarget {

    override fun createPointer(): Pointer<out GenServerHandlerTarget> {
        val presentationText = this.presentationText
        // Anchor to the enclosing call-definition clause (a stable ancestor) rather than to the
        // name-identifier element or a bare file range, so the name-identifier range is recomputed
        // correctly on restore even if the clause is edited.
        val clause = generateSequence(file.findElementAt(range.startOffset)) { it.parent }
            .filterIsInstance<Call>()
            .firstOrNull { CallDefinitionClause.`is`(it) && CallDefinitionClause.nameElement(it)?.let(UseSymbols::nameRange) == range }
        if (clause != null) {
            val clausePointer = SmartPointerManager.getInstance(file.project)
                .createSmartPsiElementPointer(clause, file)
            return Pointer {
                val restoredClause = clausePointer.dereference() ?: return@Pointer null
                val restoredRange = CallDefinitionClause.nameElement(restoredClause)?.let(UseSymbols::nameRange)
                    ?: return@Pointer null
                GenServerHandlerTarget(restoredClause.containingFile, restoredRange, presentationText)
            }
        }
        return Pointer.fileRangePointer(file, range) { restoredFile, restoredRange ->
            GenServerHandlerTarget(restoredFile, restoredRange, presentationText)
        }
    }

    override fun computePresentation(): TargetPresentation =
        TargetPresentation.builder(presentationText).icon(AllIcons.Nodes.Method).presentation()

    override fun navigationRequest(): NavigationRequest? =
        NavigationRequest.sourceNavigationRequest(file, range)

    override fun equals(other: Any?): Boolean =
        other is GenServerHandlerTarget && other.file == file && other.range == range

    override fun hashCode(): Int = Objects.hash(file, range)

    override fun toString(): String = "GenServerHandlerTarget($presentationText)"
}
