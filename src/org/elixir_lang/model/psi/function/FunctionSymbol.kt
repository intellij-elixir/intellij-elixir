package org.elixir_lang.model.psi.function

import com.intellij.find.usages.api.SearchTarget
import com.intellij.find.usages.api.UsageHandler
import com.intellij.icons.AllIcons
import com.intellij.model.Pointer
import com.intellij.openapi.util.TextRange
import com.intellij.platform.backend.navigation.NavigationRequest
import com.intellij.platform.backend.navigation.NavigationTarget
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.refactoring.rename.api.RenameValidationResult
import com.intellij.refactoring.rename.api.RenameValidator
import com.intellij.psi.search.SearchScope
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.model.psi.ElixirSymbolWithUsages
import org.elixir_lang.psi.CallableDeclaration
import org.elixir_lang.psi.DelegationPrecedence
import org.elixir_lang.psi.Protocol
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.nameTextRange
import org.elixir_lang.psi.scope.call_definition_clause.MultiResolve
import com.intellij.psi.PsiElement
import org.elixir_lang.model.psi.atom.AtomSymbol
import java.util.*

/**
 * Symbol for one arity of a function or macro a module declares - by a clause, a `defdelegate` or an EEx
 * `function_from_*` - outside a `defprotocol`, whose are [org.elixir_lang.model.psi.protocol.ProtocolFunction]s.
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
    override val arity: Int,
    val macro: Boolean,
    /** Go To follows the `defdelegate`'s `to:` from it, as [followingDelegation] marks; not part of its identity. */
    val followsDelegation: Boolean = false,
    /** Reached from a call at an arity nothing declares, which does not compile; not part of its identity. */
    val rejectedCall: Boolean = false,
    /** The [org.elixir_lang.psi.ArityInterval.functionArity] of its declaration, `null` when open; [function] reads it. */
    val functionArity: Int? = arity,
    /** The arity a use named it at, above [arity] for an [open] function; [delegatedTo] reads it. Not part of its identity. */
    private val usedArity: Int = arity
) : ElixirSymbolWithUsages, NavigationTarget, SearchTarget, org.elixir_lang.psi.DelegationSymbol<FunctionSymbol> {

    override val searchText: String get() = name
    override val targetName: String get() = name

    override fun createPointer(): Pointer<out FunctionSymbol> {
        val moduleName = this.moduleName
        val name = this.name
        val arity = this.arity
        val macro = this.macro
        val followsDelegation = this.followsDelegation
        val rejectedCall = this.rejectedCall
        val functionArity = this.functionArity
        val usedArity = this.usedArity

        return declarationPointer(file, range) { restoredFile, restoredRange ->
            FunctionSymbol(restoredFile, restoredRange, moduleName, name, arity, macro, followsDelegation, rejectedCall, functionArity, usedArity)
        }
    }

    // --- NavigationTarget ---
    override fun computePresentation(): TargetPresentation = presentation()

    override fun navigationRequest(): NavigationRequest? =
        (landing() ?: this).let { NavigationRequest.sourceNavigationRequest(it.file, it.range) }

    /** Where following delegations from this symbol ends; `null` if they delegate round to one already followed. */
    private fun landing(): FunctionSymbol? {
        val followed = mutableSetOf<FunctionSymbol>()
        var symbol = this

        while (symbol.followsDelegation) {
            if (!followed.add(symbol)) return null
            symbol = symbol.delegatedTo().firstOrNull() ?: return symbol
        }

        return symbol
    }

    override fun followingDelegation(usedArity: Int): FunctionSymbol =
        FunctionSymbol(
            file, range, moduleName, name, arity, macro,
            followsDelegation = true, rejectedCall = false, functionArity = functionArity, usedArity = usedArity
        )

    /** This symbol as a call that does not compile offers it: to search for and label, not to rename. */
    fun offeredToARejectedCall(): FunctionSymbol =
        FunctionSymbol(
            file, range, moduleName, name, arity, macro,
            followsDelegation = false, rejectedCall = true, functionArity = functionArity
        )

    /**
     * The function this symbol is one arity of: a bodiless head's defaults and the clauses after it are one function, so
     * a use of any of its arities is a use of it. An open head's only symbol is at its minimum, and it is no other's.
     */
    val function: Function get() = Function(moduleName, name, macro, functionArity ?: arity, open)

    data class Function(val moduleName: String, val name: String, val macro: Boolean, val arity: Int, val open: Boolean)

    override val open: Boolean get() = functionArity == null

    /** Whether [other] is one arity of the same [function]. */
    fun sameFunction(other: FunctionSymbol): Boolean = other.function == function

    override fun validator(): RenameValidator =
        if (rejectedCall) RejectedCallValidator else super.validator()

    /** What the `defdelegate` declaring this symbol delegates to when used as it was; empty for any other declaration. */
    @RequiresReadLock
    fun delegatedTo(): List<FunctionSymbol> {
        val delegation = CallableDeclaration.declarationNamedAt(file, range)
            ?.takeIf(DelegationPrecedence::isDelegation)
            ?: return emptyList()

        return delegatedTo(delegation, name, usedArity).filterNot { it.sameFunction(this) }
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

    private object RejectedCallValidator : RenameValidator {
        override fun validate(newName: String): RenameValidationResult =
            RenameValidationResult.invalid("A call that does not compile cannot be renamed")
    }

    companion object {
        /**
         * What [delegation], used as [name] at [usedArity], delegates to. A delegation needs no symbol of its own for
         * this, as one a `quote` injects has none.
         */
        @RequiresReadLock
        fun delegatedTo(delegation: Call, name: String, usedArity: Int): List<FunctionSymbol> {
            val targets = MultiResolve.delegatedTargets(delegation, name, usedArity, incompleteCode = false)

            return targets.definitions
                .filter { it.isValid }
                .map { PsiElementResolveResult(it.definition) }
                .toList()
                .let { functionSymbolsReached(it, targets.arity) }
                .filterIsInstance<FunctionSymbol>()
        }

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
        @RequiresReadLock
        fun of(
            file: PsiFile,
            nameElement: PsiElement,
            moduleName: String,
            name: String,
            arity: Int,
            macro: Boolean,
            functionArity: Int? = arity
        ) = FunctionSymbol(file, nameTextRange(nameElement), moduleName, name, arity, macro, functionArity = functionArity)

        /** What [declaration] declares at [arity], which is what a use at that arity names. */
        @RequiresReadLock
        fun at(declaration: Call, arity: Int): List<FunctionSymbol> = fromDeclaration(declaration).filter { it.namedAt(arity) }
        /** The [Function] [call] declares, `null` when it declares none. */
        @RequiresReadLock
        fun functionOf(call: Call): Function? = fromDeclaration(call).firstOrNull()?.function

        /** The function an [AtomSymbol] names, anchored where it is. */
        fun of(atom: AtomSymbol) =
            FunctionSymbol(
                atom.file, atom.range, atom.moduleName, atom.name, atom.arity, atom.macro, atom.followsDelegation,
                functionArity = atom.functionArity, usedArity = atom.usedArity
            )

        /**
         * The symbols [call] declares in a regular module, one per arity; none directly inside a `defprotocol` (those are
         * `ProtocolFunction`s), or for a form with no name of its own. The file is the navigable one: a decompiled
         * function's clause lives in an in-memory mirror whose `originalFile` opens the decompiled editor.
         */
        @RequiresReadLock
        fun fromDeclaration(call: Call): List<FunctionSymbol> {
            val named = CallableDeclaration.named(call)?.takeUnless { Protocol.`is`(it.modular) } ?: return emptyList()
            val file = call.containingFile.originalFile

            return named.declarations.flatMap { declaration ->
                declaration.arityInterval?.let { interval ->
                    interval.closed().map { arity ->
                        of(file, named.nameElement, named.moduleName, declaration.name, arity, named.compileTime, interval.functionArity)
                    }
                }.orEmpty()
            }
        }
    }
}
