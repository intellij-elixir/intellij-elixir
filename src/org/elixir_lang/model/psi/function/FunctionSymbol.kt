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
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.ResolveState
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.search.SearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.declaration.Capabilities
import org.elixir_lang.declaration.Presentation
import org.elixir_lang.model.psi.ElixirRenameTarget
import org.elixir_lang.model.psi.ElixirSymbolWithUsages
import org.elixir_lang.model.psi.callback.CallbackImplementation
import org.elixir_lang.model.psi.protocol.ProtocolFunction
import org.elixir_lang.model.psi.protocol.ProtocolImplementation
import org.elixir_lang.navigation.ElixirClausePresentation
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.DefinitionDeclaration
import org.elixir_lang.psi.Implementation
import org.elixir_lang.psi.Protocol
import org.elixir_lang.psi.arityInterval
import org.elixir_lang.psi.call.Call
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
) : ElixirRenameTarget, NavigationTarget, SearchTarget {

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
        val clause = madeFromClause(file, range)
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
            .firstOrNull { CallDefinitionClause.`is`(it) }
            ?: return null

        return ElixirClausePresentation.elementText(clause)
    }

    /**
     * The callbacks and protocol functions the definition this symbol is made from, a clause or a `defdelegate`,
     * implements.
     */
    @RequiresReadLock
    fun implementedMembers(): List<ElixirSymbolWithUsages> =
        madeFromDefinition(file, range)
            ?.let { CallbackImplementation.implementedCallbacks(it) + ProtocolImplementation.membersImplementedBy(it) }
            .orEmpty()

    @RequiresReadLock
    override fun renamedWith(): List<ElixirSymbolWithUsages> = implementedMembers()

    override fun equals(other: Any?): Boolean =
        other is FunctionSymbol &&
            other.moduleName == moduleName &&
            other.name == name &&
            other.arity == arity &&
            other.macro == macro

    override fun hashCode(): Int = Objects.hash(moduleName, name, arity, macro)

    override fun toString(): String = "FunctionSymbol($moduleName.$name/$arity, macro=$macro)"

    companion object {
        /** Whether a declaration with [capabilities] is presented as a macro: a guard is, as it is expanded at compile time. */
        fun isMacro(capabilities: Capabilities): Boolean =
            when (capabilities.presentation) {
                Presentation.FUNCTION -> false
                Presentation.MACRO, Presentation.GUARD -> true
            }

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
            if (!CallDefinitionClause.`is`(clause)) return emptyList()
            val enclosingModular = CallDefinitionClause.enclosingModularMacroCall(clause) ?: return emptyList()
            // Protocol function declarations are owned by ProtocolFunction, not FunctionSymbol.
            if (Protocol.`is`(enclosingModular)) return emptyList()
            val moduleName = runCatching { org.elixir_lang.psi.Module.name(enclosingModular) }
                .getOrElse { if (it is ProcessCanceledException) throw it else null }
                ?: return emptyList()
            val nameArity = CallDefinitionClause.nameArityInterval(clause, ResolveState.initial()) ?: return emptyList()
            val nameId = CallDefinitionClause.nameIdentifier(clause) ?: return emptyList()
            val macro = CallDefinitionClause.capabilities(clause)?.let(::isMacro) == true
            // For a decompiled beam function, this clause lives in an in-memory mirror file built from the `.beam`'s
            // decompiled text; its `originalFile` is the navigable compiled file whose virtual file opens the
            // decompiled editor at these offsets. For a source function `originalFile` is the file itself (no-op).
            val declarationFile = clause.containingFile.originalFile
            return nameArity.arityInterval.closed().map { arity ->
                FunctionSymbol(declarationFile, nameId.textRange, moduleName, nameArity.name, arity, macro)
            }
        }

        /**
         * The functions [definition] defines: a clause, a `.beam` definition through its decompiled clause, or a
         * `defdelegate`. Empty for anything else.
         */
        @RequiresReadLock
        fun fromDefinition(definition: PsiElement): List<FunctionSymbol> {
            definitionClause(definition)?.let { return fromClause(it) }

            return (definition as? Call)?.takeIf { Delegation.`is`(it) }?.let { fromDelegation(it) }.orEmpty()
        }

        /**
         * Whether a use at [at] that reaches [definition] is a use of [symbol]: the one place that says so.
         *
         * A clause is a use of every symbol equal to the one it defines, except in a `defimpl`. There every
         * implementation of a function has one symbol, the protocol's, so a definition that implements no protocol
         * function, such as a helper, is a use of [symbol] only when [symbol] is made from a clause of the same
         * function in the same `defimpl`. A `defdelegate` is a use of
         * the symbol made from it alone, found by its file and head range, since a sibling `defimpl`'s `defdelegate`
         * has an equal symbol; and never at its own head, which its declaration already gives.
         */
        @RequiresReadLock
        fun matches(definition: PsiElement, symbol: FunctionSymbol, at: PsiElement): Boolean {
            definitionClause(definition)?.let { return matchesClause(it, symbol) }

            val delegation = (definition as? Call)?.takeIf { Delegation.`is`(it) } ?: return false
            val head = Delegation.nameIdentifier(delegation) ?: return false
            val atOwnHead = PsiTreeUtil.isAncestor(head, at, false) || PsiTreeUtil.isAncestor(at, head, false)

            return !atOwnHead && symbol in fromDelegation(delegation) && isMadeFrom(symbol, delegation, head)
        }

        /**
         * Whether an atom at [atom] that reaches [definition] names [symbol]: as [matches] says, and a protocol's own
         * declaration too, which a call reaches as a `ProtocolFunction` and an atom names by the protocol's module.
         */
        @RequiresReadLock
        fun matchesAtom(definition: PsiElement, symbol: FunctionSymbol, atom: PsiElement): Boolean {
            if (matches(definition, symbol, atom)) return true

            val clause = definitionClause(definition) ?: return false

            return ProtocolFunction.fromClause(clause).isNotEmpty() && isFamilyMember(clause, symbol)
        }

        /**
         * Whether [symbol] is made from a clause in a `defimpl` that implements no protocol function. Its module name is
         * the protocol's, so it is no function of a qualified call that names that module.
         */
        @RequiresReadLock
        fun isHelper(symbol: FunctionSymbol): Boolean = helperImplementation(symbol) != null

        /**
         * What tells two definitions apart where [matches] cannot rely on symbol equality: a clause's symbols, each with
         * the `defimpl` it is tied to when its clause implements nothing, and a `defdelegate`'s file and head range.
         */
        @RequiresReadLock
        fun definitionKey(definition: PsiElement): Any? {
            definitionClause(definition)?.let { clause -> return fromClause(clause).map { it to helperImplementation(it) } }

            val delegation = (definition as? Call)?.takeIf { Delegation.`is`(it) } ?: return null
            val head = Delegation.nameIdentifier(delegation) ?: return null

            return delegation.containingFile.originalFile.let { it.virtualFile ?: it } to head.textRange
        }

        /**
         * Whether [clause] is a declaration in [symbol]'s function family: a clause of the same module, name, arity
         * and kind, the protocol's own included. A clause in a `defimpl` is a member as [matches] says.
         */
        @RequiresReadLock
        fun isFamilyMember(clause: Call, symbol: FunctionSymbol): Boolean {
            val enclosingModular = CallDefinitionClause.enclosingModularMacroCall(clause) ?: return false
            if (Implementation.`is`(enclosingModular)) return matchesClause(clause, symbol)
            if (helperImplementation(symbol) != null) return false

            val moduleName = runCatching { org.elixir_lang.psi.Module.name(enclosingModular) }
                .getOrElse { if (it is ProcessCanceledException) throw it else null }
                ?: return false
            if (moduleName != symbol.moduleName) return false

            val nameArity = CallDefinitionClause.nameArityInterval(clause, ResolveState.initial()) ?: return false

            return nameArity.name == symbol.name &&
                symbol.arity in nameArity.arityInterval &&
                (CallDefinitionClause.capabilities(clause)?.let(::isMacro) == true) == symbol.macro
        }

        @RequiresReadLock
        private fun matchesClause(clause: Call, symbol: FunctionSymbol): Boolean {
            if (symbol !in fromClause(clause)) return false

            val enclosingModular = CallDefinitionClause.enclosingModularMacroCall(clause) ?: return false
            if (!Implementation.`is`(enclosingModular)) return true

            helperImplementation(symbol)?.let { return it == enclosingModular }

            return implements(clause, symbol)
        }

        /**
         * The `defimpl` of the clause [symbol] is made from when that clause implements no protocol function, whether a
         * `defp` or a public `def` the protocol does not declare, else `null`.
         */
        @RequiresReadLock
        private fun helperImplementation(symbol: FunctionSymbol): Call? {
            val clause = madeFromClause(symbol.file, symbol.range) ?: return null
            val enclosingModular = CallDefinitionClause.enclosingModularMacroCall(clause)?.takeIf { Implementation.`is`(it) }
                ?: return null

            return enclosingModular.takeUnless { implements(clause, symbol) }
        }

        /** Whether [clause], in a `defimpl`, implements the protocol function [symbol] is named by. */
        @RequiresReadLock
        private fun implements(clause: Call, symbol: FunctionSymbol): Boolean =
            ProtocolImplementation.implementsMember(clause, symbol.name, symbol.arity, symbol.macro)

        /** The clause or `defdelegate` whose name identifier is [range] in [file]. */
        @RequiresReadLock
        private fun madeFromDefinition(file: PsiFile, range: TextRange): Call? =
            generateSequence(file.findElementAt(range.startOffset)) { it.parent }
                .filterIsInstance<Call>()
                .firstOrNull { DefinitionDeclaration.`is`(it) && DefinitionDeclaration.nameIdentifier(it)?.textRange == range }

        /** The clause whose name identifier is [range] in [file]. */
        @RequiresReadLock
        private fun madeFromClause(file: PsiFile, range: TextRange): Call? =
            madeFromDefinition(file, range)?.takeIf { CallDefinitionClause.`is`(it) }

        private fun isMadeFrom(symbol: FunctionSymbol, definition: Call, head: PsiElement): Boolean =
            definition.containingFile.originalFile.virtualFile == symbol.file.virtualFile &&
                head.textRange == symbol.range

        /**
         * The symbols a `defdelegate` declares in its own module.
         *
         * A delegation declares a function whether or not `to:` resolves, and [fromClause] cannot
         * express it because `Delegation.is` and `CallDefinitionClause.is` are disjoint.
         */
        @RequiresReadLock
        fun fromDelegation(delegation: Call): List<FunctionSymbol> {
            val declaration = Delegation.declaration(delegation, ResolveState.initial()) ?: return emptyList()
            val enclosingModular = CallDefinitionClause.enclosingModularMacroCall(delegation) ?: return emptyList()
            if (Protocol.`is`(enclosingModular)) return emptyList()
            val moduleName = runCatching { org.elixir_lang.psi.Module.name(enclosingModular) }
                .getOrElse { if (it is ProcessCanceledException) throw it else null }
                ?: return emptyList()
            val arityInterval = declaration.arity.arityInterval() ?: return emptyList()
            val nameId = Delegation.nameIdentifier(delegation) ?: return emptyList()
            val declarationFile = delegation.containingFile.originalFile

            return arityInterval.closed().map { arity ->
                FunctionSymbol(
                    declarationFile,
                    nameId.textRange,
                    moduleName,
                    declaration.name,
                    arity,
                    isMacro(declaration.capabilities)
                )
            }
        }
    }
}
