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
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiFile
import com.intellij.psi.ResolveState
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.search.SearchScope
import com.intellij.refactoring.rename.api.RenameTarget
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.model.psi.ElixirSymbolWithUsages
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.CallableDeclaration
import org.elixir_lang.psi.Protocol
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.nameTextRange
import org.elixir_lang.psi.scope.call_definition_clause.MultiResolve
import com.intellij.psi.PsiElement
import org.elixir_lang.model.psi.atom.AtomSymbol
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
class FunctionSymbol private constructor(
    override val file: PsiFile,
    override val range: TextRange,
    val moduleName: String,
    val name: String,
    val arity: Int,
    val macro: Boolean,
    /** Reached from a use of a `defdelegate`, so Go To follows its `to:`; not part of its identity. */
    val followsDelegation: Boolean = false
) : ElixirSymbolWithUsages, NavigationTarget, SearchTarget, RenameTarget {

    override val searchText: String get() = name
    override val targetName: String get() = name

    override fun createPointer(): Pointer<out FunctionSymbol> {
        val moduleName = this.moduleName
        val name = this.name
        val arity = this.arity
        val macro = this.macro
        val followsDelegation = this.followsDelegation

        return declarationPointer(file, range) { restoredFile, restoredRange ->
            FunctionSymbol(restoredFile, restoredRange, moduleName, name, arity, macro, followsDelegation)
        }
    }

    // --- NavigationTarget ---
    override fun computePresentation(): TargetPresentation = presentation()

    override fun navigationRequest(): NavigationRequest? =
        (if (followsDelegation) delegatedTo().firstOrNull()?.navigationRequest() else null)
            ?: NavigationRequest.sourceNavigationRequest(file, range)

    /** This symbol as a use reaches it: a `defdelegate`'s Go To follows its `to:`. */
    fun reachedFromAUse(): FunctionSymbol = FunctionSymbol(file, range, moduleName, name, arity, macro, true)

    /** What the `defdelegate` declaring this symbol delegates to at its arity; empty for any other declaration. */
    @RequiresReadLock
    fun delegatedTo(): List<FunctionSymbol> {
        val delegation = CallableDeclaration.declarationNamedAt(file, range)
            ?.takeIf { CallableDeclaration.isForm(it, CallableDeclaration.Form.DELEGATION) }
            ?: return emptyList()
        val resolved = MultiResolve.delegatedTargets(delegation, name, arity, incompleteCode = false)
            .flatten()
            .filter { it.isValid }
            .map { PsiElementResolveResult(it.definition) }
            .toList()

        // Told apart by where they are, not by equality: under an `if` both would name the `if` as their module.
        return functionSymbolsReached(resolved, arity)
            .filterIsInstance<FunctionSymbol>()
            .filterNot { it.file == file && it.range == range }
    }

    // --- SearchTarget ---
    override val maximalSearchScope: SearchScope? get() = null

    override val usageHandler: UsageHandler
        get() = UsageHandler.createEmptyUsageHandler("$name/$arity")

    override fun presentation(): TargetPresentation =
        TargetPresentation.builder(
            CallableDeclaration.declarationNamedAt(file, range)?.let(CallableDeclaration::label) ?: "$moduleName.$name/$arity"
        )
            .containerText(moduleName)
            .icon(if (macro) AllIcons.Nodes.AbstractMethod else AllIcons.Nodes.Method)
            .presentation()

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
         * A pointer to the symbol named at [range], anchored to the declaration spelling that name rather than to the
         * name itself: an in-place (Shift+F6) rename replaces the name's leaf and collapses a plain range marker, so the
         * commit would edit the wrong range. The declaration survives, and the name's range is re-read on restore. A
         * compiled definition has no source declaration and keeps a range pointer.
         */
        @RequiresReadLock
        fun <T : Any> declarationPointer(file: PsiFile, range: TextRange, restore: (PsiFile, TextRange) -> T): Pointer<T> {
            val declaration = CallableDeclaration.declarationNamedAt(file, range)
                ?: return Pointer.fileRangePointer(file, range, restore)
            val declarationPointer = SmartPointerManager.getInstance(file.project).createSmartPsiElementPointer(declaration, file)

            return Pointer {
                val restored = declarationPointer.dereference() ?: return@Pointer null
                val restoredRange = CallableDeclaration.nameElement(restored)?.let(::nameTextRange) ?: return@Pointer null
                restore(restored.containingFile, restoredRange)
            }
        }

        /** The symbol whose name [nameElement] spells, its range read from the name alone. */
        fun of(file: PsiFile, nameElement: PsiElement, moduleName: String, name: String, arity: Int, macro: Boolean) =
            FunctionSymbol(file, nameTextRange(nameElement), moduleName, name, arity, macro)

        /** The function an [AtomSymbol] names, anchored where it is. */
        fun of(atom: AtomSymbol) =
            FunctionSymbol(atom.file, atom.range, atom.moduleName, atom.name, atom.arity, atom.macro, atom.followsDelegation)

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
            val definer = CallableDeclaration.definerOf(clause) ?: return emptyList()
            val enclosingModular = CallDefinitionClause.enclosingModularMacroCall(clause) ?: return emptyList()
            // Protocol function declarations are owned by ProtocolFunction, not FunctionSymbol.
            if (Protocol.`is`(enclosingModular)) return emptyList()
            val moduleName = runCatching { org.elixir_lang.psi.Module.name(enclosingModular) }
                .getOrElse { if (it is ProcessCanceledException) throw it else null }
                ?: return emptyList()
            val nameArity = CallDefinitionClause.nameArityInterval(clause, ResolveState.initial()) ?: return emptyList()
            val nameId = CallDefinitionClause.nameIdentifier(clause) ?: return emptyList()
            val macro = definer.capabilities.compileTime
            // For a decompiled beam function, this clause lives in an in-memory mirror file built from the `.beam`'s
            // decompiled text; its `originalFile` is the navigable compiled file whose virtual file opens the
            // decompiled editor at these offsets. For a source function `originalFile` is the file itself (no-op).
            val declarationFile = clause.containingFile.originalFile
            return nameArity.arityInterval.closed().map { arity ->
                of(declarationFile, nameId, moduleName, nameArity.name, arity, macro)
            }
        }

        /** The symbols [call] declares, whichever form - clause, `defdelegate` or EEx `function_from_*` - it is. */
        @RequiresReadLock
        fun fromDeclaration(call: Call): List<FunctionSymbol> =
            when (CallableDeclaration.formOf(call, ResolveState.initial())) {
                CallableDeclaration.Form.CLAUSE -> fromClause(call)
                CallableDeclaration.Form.DELEGATION -> fromDelegation(call)
                CallableDeclaration.Form.EEX_FUNCTION_FROM -> fromEExFunctionFrom(call)
                // A `@callback` declares what another module defines; the others have no symbol of their own yet.
                CallableDeclaration.Form.CALLBACK, CallableDeclaration.Form.EXCEPTION,
                CallableDeclaration.Form.GENERATOR_EMBED, null -> emptyList()
            }

        /**
         * The symbol an EEx `function_from_*` [call] declares in its own module, anchored at the name inside its name
         * atom; none when the name or the arity is not literal. [call] is already known to be one.
         */
        private fun fromEExFunctionFrom(call: Call): List<FunctionSymbol> {
            val nameAtom = org.elixir_lang.EEx.declaredNameAtom(call) ?: return emptyList()
            val name = org.elixir_lang.EEx.declaredName(call) ?: return emptyList()
            val arity = org.elixir_lang.EEx.argumentList(call)?.size ?: return emptyList()
            val enclosingModular = CallDefinitionClause.enclosingModularMacroCall(call) ?: return emptyList()
            val moduleName = runCatching { org.elixir_lang.psi.Module.name(enclosingModular) }
                .getOrElse { if (it is ProcessCanceledException) throw it else null }
                ?: return emptyList()
            return listOf(of(call.containingFile.originalFile, nameAtom, moduleName, name, arity, CallableDeclaration.isCompileTime(call)))
        }

        /**
         * The symbols a `defdelegate` declares in its own module.
         *
         * A delegation declares a function whether or not `to:` resolves, and [fromClause] cannot
         * express it because [CallableDeclaration.Form.DELEGATION] and [CallableDeclaration.Form.CLAUSE] are
         * separate forms.
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
            val macro = CallableDeclaration.isCompileTime(delegation)

            return nameArity.arityInterval.closed().map { arity ->
                of(declarationFile, nameId, moduleName, nameArity.name, arity, macro)
            }
        }
    }
}
