package org.elixir_lang.model.psi.protocol

import com.intellij.find.usages.api.SearchTarget
import com.intellij.find.usages.api.UsageHandler
import com.intellij.icons.AllIcons
import com.intellij.model.Pointer
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.TextRange
import com.intellij.platform.backend.navigation.NavigationRequest
import com.intellij.platform.backend.navigation.NavigationTarget
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiFile
import com.intellij.psi.ResolveState
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.search.SearchScope
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.model.psi.ElixirSymbolWithUsages
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.CallableDeclaration
import org.elixir_lang.psi.Protocol
import org.elixir_lang.psi.call.Call
import java.util.*

/**
 * Symbol representing a single `def`/`defmacro` clause declared inside a `defprotocol`.
 *
 * "Usages" are the **call sites** that dispatch to it (`Protocol.function(args)` of matching
 * name/arity) plus the implementing `def`/`defmacro` clauses inside each `defimpl`, computed by
 * `ElixirSymbolUsageSearcher`. Rename updates the implementations so the protocol member and its
 * implementations stay in sync; for Find Usages/navigation the implementations are also reachable
 * via "Go To Implementation" (`Ctrl+Alt+B`) / the gutter marker.
 *
 * NOTE: this is intentionally a regular class, not a `data class` - [equals]/[hashCode] are by
 * semantic identity `(protocolName, name, arity, macro)` and must NOT include [file]/[range].
 */
@Suppress("UnstableApiUsage")
class ProtocolFunction(
    override val file: PsiFile,
    override val range: TextRange,
    val protocolName: String,
    val name: String,
    val arity: Int,
    val macro: Boolean
) : ElixirSymbolWithUsages, NavigationTarget, SearchTarget {

    override val searchText: String get() = name
    override val targetName: String get() = name

    override fun createPointer(): Pointer<out ProtocolFunction> {
        val protocolName = this.protocolName
        val name = this.name
        val arity = this.arity
        val macro = this.macro
        // Anchor to the enclosing call-definition clause (a stable ancestor) rather than to the
        // name-identifier element or a bare file range: an in-place (Shift+F6) rename fully replaces
        // the identifier's text, which swaps out the identifier leaf (collapsing a pointer anchored to
        // it) and collapses a plain range marker to an empty range - either way the subsequent
        // programmatic commit edits the wrong range and applies nothing. The clause survives the
        // identifier replacement, so its name-identifier range is recomputed correctly on restore.
        val clause = generateSequence(file.findElementAt(range.startOffset)) { it.parent }
            .filterIsInstance<Call>()
            .firstOrNull { CallDefinitionClause.`is`(it) && CallDefinitionClause.nameIdentifier(it)?.textRange == range }
        if (clause != null) {
            val clausePointer = SmartPointerManager.getInstance(file.project)
                .createSmartPsiElementPointer(clause, file)
            return Pointer {
                val restoredClause = clausePointer.dereference() ?: return@Pointer null
                val restoredRange = CallDefinitionClause.nameIdentifier(restoredClause)?.textRange
                    ?: return@Pointer null
                ProtocolFunction(restoredClause.containingFile, restoredRange, protocolName, name, arity, macro)
            }
        }
        return Pointer.fileRangePointer(file, range) { restoredFile, restoredRange ->
            ProtocolFunction(restoredFile, restoredRange, protocolName, name, arity, macro)
        }
    }

    // --- NavigationTarget ---
    override fun computePresentation(): TargetPresentation = presentation()

    override fun navigationRequest(): NavigationRequest? =
        NavigationRequest.sourceNavigationRequest(file, range)

    // --- SearchTarget ---
    override val maximalSearchScope: SearchScope? get() = null

    override val usageHandler: UsageHandler
        get() = UsageHandler.createEmptyUsageHandler("$name/$arity")

    override fun presentation(): TargetPresentation =
        TargetPresentation.builder("$protocolName.$name/$arity")
            .icon(AllIcons.Nodes.AbstractMethod)
            .presentation()

    override fun equals(other: Any?): Boolean =
        other is ProtocolFunction &&
            other.protocolName == protocolName &&
            other.name == name &&
            other.arity == arity &&
            other.macro == macro

    override fun hashCode(): Int = Objects.hash(protocolName, name, arity, macro)

    override fun toString(): String = "ProtocolFunction($protocolName.$name/$arity, macro=$macro)"

    companion object {
        /**
         * Build the [ProtocolFunction] symbol(s) for a `def`/`defmacro` clause directly inside
         * a `defprotocol`. Returns empty list if the clause is not inside a `defprotocol`, or if
         * name/arity/protocol cannot be determined.
         */
        @RequiresReadLock
        fun fromClause(clause: Call): List<ProtocolFunction> {
            if (!CallDefinitionClause.`is`(clause)) return emptyList()
            val defprotocol = CallDefinitionClause.enclosingModularMacroCall(clause) ?: return emptyList()
            if (!Protocol.`is`(defprotocol)) return emptyList()
            val protocolName = runCatching { org.elixir_lang.psi.Module.name(defprotocol) }
                .getOrElse { if (it is ProcessCanceledException) throw it else null }
                ?: return emptyList()
            val nameArity = CallDefinitionClause.nameArityInterval(clause, ResolveState.initial()) ?: return emptyList()
            val nameId = CallDefinitionClause.nameIdentifier(clause) ?: return emptyList()
            val macro = CallableDeclaration.isCompileTime(clause)
            return nameArity.arityInterval.closed().map { arity ->
                ProtocolFunction(clause.containingFile, nameId.textRange, protocolName, nameArity.name, arity, macro)
            }
        }
    }
}
