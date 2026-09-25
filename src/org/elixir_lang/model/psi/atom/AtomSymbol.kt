package org.elixir_lang.model.psi.atom

import org.elixir_lang.psi.impl.nameTextRange
import com.intellij.find.usages.api.SearchTarget
import com.intellij.find.usages.api.UsageHandler
import com.intellij.icons.AllIcons
import com.intellij.model.Pointer
import com.intellij.openapi.util.TextRange
import com.intellij.platform.backend.navigation.NavigationRequest
import com.intellij.platform.backend.navigation.NavigationTarget
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.SearchScope
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.model.psi.ElixirSymbolWithUsages
import org.elixir_lang.model.psi.protocol.ProtocolFunction
import org.elixir_lang.navigation.ElixirClausePresentation
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.CallableDeclaration
import org.elixir_lang.psi.call.Call
import java.util.*
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition

@Suppress("UnstableApiUsage")
class AtomSymbol private constructor(
    override val file: PsiFile,
    override val range: TextRange,
    val moduleName: String,
    val name: String,
    override val arity: Int,
    val macro: Boolean,
    private val displayText: String? = null,
    /** Go To follows the `defdelegate`'s `to:` from it, as [followingDelegation] marks; not part of its identity. */
    val followsDelegation: Boolean = false,
    /** The [org.elixir_lang.psi.ArityInterval.functionArity] of its declaration; not part of its identity. */
    val functionArity: Int? = arity,
    /** Names a `defprotocol` function, a [ProtocolFunction], rather than a [org.elixir_lang.model.psi.function.FunctionSymbol]. */
    val protocol: Boolean = false,
    /** The arity a use named it at, above [arity] for an [open] function; not part of its identity. */
    val usedArity: Int = arity
) : ElixirSymbolWithUsages, NavigationTarget, SearchTarget, org.elixir_lang.psi.DelegationSymbol<AtomSymbol> {
    override val searchText: String get() = name
    override val targetName: String get() = name
    override val open: Boolean get() = functionArity == null

    override fun createPointer(): Pointer<out AtomSymbol> {
        val moduleName = this.moduleName
        val name = this.name
        val arity = this.arity
        val macro = this.macro
        val displayText = this.displayText
        val followsDelegation = this.followsDelegation
        val functionArity = this.functionArity
        val protocol = this.protocol
        val usedArity = this.usedArity

        return org.elixir_lang.model.psi.function.FunctionSymbol.declarationPointer(file, range) { restoredFile, restoredRange ->
            AtomSymbol(restoredFile, restoredRange, moduleName, name, arity, macro, displayText, followsDelegation, functionArity, protocol, usedArity)
        }
    }

    override fun computePresentation(): TargetPresentation = presentation()

    override fun navigationRequest(): NavigationRequest? =
        if (followsDelegation) {
            org.elixir_lang.model.psi.function.FunctionSymbol.of(this).navigationRequest()
        } else {
            NavigationRequest.sourceNavigationRequest(file, range)
        }

    override fun followingDelegation(usedArity: Int): AtomSymbol =
        AtomSymbol(file, range, moduleName, name, arity, macro, displayText, true, functionArity, protocol, usedArity)

    override val maximalSearchScope: SearchScope? get() = null

    override val usageHandler: UsageHandler
        get() = UsageHandler.createEmptyUsageHandler("$name/$arity")

    override fun presentation(): TargetPresentation =
        TargetPresentation.builder(clausePresentationText() ?: "$moduleName.$name/$arity")
            .containerText(moduleName)
            .icon(if (macro) AllIcons.Nodes.AbstractMethod else AllIcons.Nodes.Method)
            .presentation()

    @RequiresReadLock
    private fun clausePresentationText(): String? =
        displayText ?: CallableDeclaration.declarationNamedAt(file, range)?.let(CallableDeclaration::label)

    /** By what it names, as a [org.elixir_lang.model.psi.function.FunctionSymbol] is: a head and its clauses are one target. */
    override fun equals(other: Any?): Boolean =
        other is AtomSymbol &&
            other.moduleName == moduleName &&
            other.name == name &&
            other.arity == arity &&
            other.macro == macro

    override fun hashCode(): Int = Objects.hash(moduleName, name, arity, macro)

    override fun toString(): String = "AtomSymbol($moduleName.$name/$arity, macro=$macro)"

    @RequiresReadLock
    fun declarationElement(): PsiElement? = CallableDeclaration.declarationNamedAt(file, range)

    /** The symbol this atom names, as its declaration's owner has it. */
    fun named(): ElixirSymbolWithUsages =
        if (protocol) {
            ProtocolFunction(file, range, moduleName, name, arity, macro)
        } else {
            org.elixir_lang.model.psi.function.FunctionSymbol.of(this)
        }

    companion object {
        /** The symbol whose name [nameElement] spells, its range read from the name alone. */
        fun of(
            file: PsiFile,
            nameElement: PsiElement,
            moduleName: String,
            name: String,
            arity: Int,
            macro: Boolean,
            displayText: String? = null,
            functionArity: Int? = arity
        ) = AtomSymbol(file, nameTextRange(nameElement), moduleName, name, arity, macro, displayText, functionArity = functionArity)

        /** The atom naming a [FunctionSymbol], anchored where it is. */
        fun of(function: org.elixir_lang.model.psi.function.FunctionSymbol) =
            AtomSymbol(
                function.file,
                function.range,
                function.moduleName,
                function.name,
                function.arity,
                function.macro,
                followsDelegation = function.followsDelegation,
                functionArity = function.functionArity
            )

        /** The atom naming a [ProtocolFunction], anchored where it is. */
        fun of(function: ProtocolFunction) =
            AtomSymbol(
                function.file,
                function.range,
                function.protocolName,
                function.name,
                function.arity,
                function.macro,
                protocol = true
            )

        /**
         * The symbols [call] declares, as whichever symbol owns it names them: a [ProtocolFunction] for a `defprotocol`'s
         * clause, else a [org.elixir_lang.model.psi.function.FunctionSymbol] for any form.
         */
        @RequiresReadLock
        fun fromDeclaration(call: Call): List<AtomSymbol> =
            ProtocolFunction.fromClause(call).map(::of) +
                org.elixir_lang.model.psi.function.FunctionSymbol.fromDeclaration(call).map(::of)

        /** What a source or compiled [declaration] declares at [arity], which is what an MFA naming that arity names. */
        @RequiresReadLock
        fun at(declaration: PsiElement, arity: Int): List<AtomSymbol> =
            when (declaration) {
                is Call -> fromDeclaration(declaration)
                is BeamCallDefinition -> fromBeamCallDefinition(declaration)
                else -> emptyList()
            }.filter { it.namedAt(arity) }

        @RequiresReadLock
        fun fromBeamCallDefinition(callDefinition: BeamCallDefinition): List<AtomSymbol> {
            val navigationClause = callDefinition.navigationElement as? Call


            val presentationText =
                if (navigationClause != null && CallDefinitionClause.`is`(navigationClause)) {
                    ElixirClausePresentation.elementText(navigationClause)
                } else {
                    null
                }
            val moduleName = callDefinition.parent.name
            val nameArity = callDefinition.nameArityInterval
            val macro = CallableDeclaration.isCompileTime(callDefinition)
            val functionArity = nameArity.arityInterval.functionArity

            return nameArity.arityInterval.closed().map { arity ->
                of(callDefinition.containingFile, callDefinition, moduleName, nameArity.name, arity, macro, presentationText, functionArity)
            }
        }
    }
}
