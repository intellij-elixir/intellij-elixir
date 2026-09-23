package org.elixir_lang.model.psi.function

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
import com.intellij.refactoring.rename.api.RenameTarget
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.model.psi.ElixirSymbolWithUsages
import org.elixir_lang.navigation.ElixirClausePresentation
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.CallableDeclaration
import org.elixir_lang.psi.Protocol
import org.elixir_lang.psi.call.Call
import org.elixir_lang.structure_view.element.CallDefinitionHead
import org.elixir_lang.structure_view.element.Delegation
import java.util.*

/**
 * Symbol representing a single `def`/`defp`/`defmacro`/`defmacrop`/`defguard`/`defguardp` clause
 * in a regular module (not inside a `defprotocol` - those are `ProtocolFunction`).
 *
 * "Usages" are the call sites that invoke it, computed by `ElixirSymbolUsageSearcher`.
 *
 * NOTE: intentionally a regular class, not a `data class` - [equals]/[hashCode] are by semantic
 * identity `(moduleName, name, arity, macro)` and must NOT include [file]/[range].
 */
@Suppress("UnstableApiUsage")
class FunctionSymbol(
    override val file: PsiFile,
    override val range: TextRange,
    val moduleName: String,
    val name: String,
    val arity: Int,
    val macro: Boolean
) : ElixirSymbolWithUsages, NavigationTarget, SearchTarget, RenameTarget {

    override val searchText: String get() = name
    override val targetName: String get() = name

    override fun createPointer(): Pointer<out FunctionSymbol> {
        val moduleName = this.moduleName
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
            .firstOrNull {
                CallableDeclaration.isForm(it, CallableDeclaration.Form.CLAUSE) &&
                    CallDefinitionClause.nameIdentifier(it)?.textRange == range
            }
        if (clause != null) {
            val clausePointer = SmartPointerManager.getInstance(file.project)
                .createSmartPsiElementPointer(clause, file)
            return Pointer {
                val restoredClause = clausePointer.dereference() ?: return@Pointer null
                val restoredRange = CallDefinitionClause.nameIdentifier(restoredClause)?.textRange
                    ?: return@Pointer null
                FunctionSymbol(restoredClause.containingFile, restoredRange, moduleName, name, arity, macro)
            }
        }
        return Pointer.fileRangePointer(file, range) { restoredFile, restoredRange ->
            FunctionSymbol(restoredFile, restoredRange, moduleName, name, arity, macro)
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
        TargetPresentation.builder(clausePresentationText() ?: "$moduleName.$name/$arity")
            .containerText(moduleName)
            .icon(if (macro) AllIcons.Nodes.AbstractMethod else AllIcons.Nodes.Method)
            .presentation()

    @RequiresReadLock
    private fun clausePresentationText(): String? {
        val leaf = file.findElementAt(range.startOffset) ?: return null
        val clause = generateSequence(leaf) { it.parent }
            .filterIsInstance<Call>()
            .firstOrNull { CallableDeclaration.isForm(it, CallableDeclaration.Form.CLAUSE) }
            ?: return null

        return ElixirClausePresentation.elementText(clause)
    }

    override fun equals(other: Any?): Boolean =
        other is FunctionSymbol &&
            other.moduleName == moduleName &&
            other.name == name &&
            other.arity == arity &&
            other.macro == macro

    override fun hashCode(): Int = Objects.hash(moduleName, name, arity, macro)

    override fun toString(): String = "FunctionSymbol($moduleName.$name/$arity, macro=$macro)"

    companion object {
        /**
         * Build the [FunctionSymbol] symbol(s) for a `def`/`defp`/`defmacro`/`defmacrop` clause in a regular
         * module (not inside a `defprotocol` - those are `ProtocolFunction`).
         *
         * Returns empty list if:
         * - [clause] is not a call-definition clause, or
         * - the clause is directly inside a `defprotocol` (use `ProtocolFunction.fromClause` instead), or
         * - the module name or name/arity cannot be determined.
         */
        @RequiresReadLock
        fun fromClause(clause: Call): List<FunctionSymbol> {
            if (!CallableDeclaration.isForm(clause, CallableDeclaration.Form.CLAUSE)) return emptyList()
            val enclosingModular = CallDefinitionClause.enclosingModularMacroCall(clause) ?: return emptyList()
            // Protocol function declarations are owned by ProtocolFunction, not FunctionSymbol.
            if (Protocol.`is`(enclosingModular)) return emptyList()
            val moduleName = runCatching { org.elixir_lang.psi.Module.name(enclosingModular) }
                .getOrElse { if (it is ProcessCanceledException) throw it else null }
                ?: return emptyList()
            val nameArity = CallDefinitionClause.nameArityInterval(clause, ResolveState.initial()) ?: return emptyList()
            val nameId = CallDefinitionClause.nameIdentifier(clause) ?: return emptyList()
            val macro = CallDefinitionClause.isMacro(clause)
            // For a decompiled beam function, this clause lives in an in-memory mirror file built from the `.beam`'s
            // decompiled text; its `originalFile` is the navigable compiled file whose virtual file opens the
            // decompiled editor at these offsets. For a source function `originalFile` is the file itself (no-op).
            val declarationFile = clause.containingFile.originalFile
            return nameArity.arityInterval.closed().map { arity ->
                FunctionSymbol(declarationFile, nameId.textRange, moduleName, nameArity.name, arity, macro)
            }
        }

        /**
         * The symbols a `defdelegate` declares in its own module.
         *
         * A delegation declares a function whether or not `to:` resolves, and [fromClause] cannot
         * express it because [CallableDeclaration.Form.DELEGATION] and [CallableDeclaration.Form.CLAUSE] are
         * separate forms. Never a macro.
         */
        @RequiresReadLock
        fun fromDelegation(delegation: Call): List<FunctionSymbol> {
            if (!CallableDeclaration.isForm(delegation, CallableDeclaration.Form.DELEGATION)) {
                return emptyList()
            }
            val head = CallableDeclaration.delegationHead(delegation) ?: return emptyList()
            val enclosingModular = CallDefinitionClause.enclosingModularMacroCall(delegation) ?: return emptyList()
            if (Protocol.`is`(enclosingModular)) return emptyList()
            val moduleName = runCatching { org.elixir_lang.psi.Module.name(enclosingModular) }
                .getOrElse { if (it is ProcessCanceledException) throw it else null }
                ?: return emptyList()
            val nameArity = CallDefinitionHead.nameArityInterval(head, ResolveState.initial()) ?: return emptyList()
            val nameId = Delegation.nameIdentifier(delegation) ?: return emptyList()
            val declarationFile = delegation.containingFile.originalFile

            return nameArity.arityInterval.closed().map { arity ->
                FunctionSymbol(declarationFile, nameId.textRange, moduleName, nameArity.name, arity, false)
            }
        }
    }
}
