package org.elixir_lang.model.psi.atom

import org.elixir_lang.psi.impl.nameTextRange
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
import com.intellij.psi.search.SearchScope
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.model.psi.ElixirSymbolWithUsages
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
    val arity: Int,
    val macro: Boolean,
    private val displayText: String? = null,
    /** Reached from a use of a `defdelegate`, so Go To follows its `to:`; not part of its identity. */
    val followsDelegation: Boolean = false
) : ElixirSymbolWithUsages, NavigationTarget, SearchTarget {
    override val searchText: String get() = name
    override val targetName: String get() = name

    override fun createPointer(): Pointer<out AtomSymbol> {
        val moduleName = this.moduleName
        val name = this.name
        val arity = this.arity
        val macro = this.macro
        val displayText = this.displayText
        val followsDelegation = this.followsDelegation

        return org.elixir_lang.model.psi.function.FunctionSymbol.declarationPointer(file, range) { restoredFile, restoredRange ->
            AtomSymbol(restoredFile, restoredRange, moduleName, name, arity, macro, displayText, followsDelegation)
        }
    }

    override fun computePresentation(): TargetPresentation = presentation()

    override fun navigationRequest(): NavigationRequest? =
        if (followsDelegation) {
            org.elixir_lang.model.psi.function.FunctionSymbol.of(this).navigationRequest()
        } else {
            NavigationRequest.sourceNavigationRequest(file, range)
        }

    /** This symbol as a use reaches it: a `defdelegate`'s Go To follows its `to:`. */
    fun reachedFromAUse(): AtomSymbol = AtomSymbol(file, range, moduleName, name, arity, macro, displayText, true)

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

    override fun equals(other: Any?): Boolean =
        other is AtomSymbol &&
            other.moduleName == moduleName &&
            other.name == name &&
            other.arity == arity &&
            other.macro == macro &&
            other.file.virtualFile == file.virtualFile &&
            other.range == range

    override fun hashCode(): Int = Objects.hash(moduleName, name, arity, macro, file.virtualFile, range)

    override fun toString(): String = "AtomSymbol($moduleName.$name/$arity, macro=$macro)"

    @RequiresReadLock
    fun declarationElement(): PsiElement? =
        generateSequence(file.findElementAt(range.startOffset)) { it.parent }
            .filterIsInstance<Call>()
            .firstOrNull { CallDefinitionClause.`is`(it) }

    companion object {
        /** The symbol whose name [nameElement] spells, its range read from the name alone. */
        fun of(
            file: PsiFile,
            nameElement: PsiElement,
            moduleName: String,
            name: String,
            arity: Int,
            macro: Boolean,
            displayText: String? = null
        ) = AtomSymbol(file, nameTextRange(nameElement), moduleName, name, arity, macro, displayText)

        /** The atom naming a [FunctionSymbol], anchored where it is. */
        fun of(function: org.elixir_lang.model.psi.function.FunctionSymbol) =
            AtomSymbol(
                function.file,
                function.range,
                function.moduleName,
                function.name,
                function.arity,
                function.macro,
                followsDelegation = function.followsDelegation
            )

        @RequiresReadLock
        fun fromClause(clause: Call): List<AtomSymbol> {
            val definer = CallableDeclaration.definerOf(clause) ?: return emptyList()
            val enclosingModular = CallDefinitionClause.enclosingModularMacroCall(clause) ?: return emptyList()
            val moduleName = runCatching { org.elixir_lang.psi.Module.name(enclosingModular) }
                .getOrElse { if (it is ProcessCanceledException) throw it else null }
                ?: return emptyList()
            val nameArity = CallDefinitionClause.nameArityInterval(clause, ResolveState.initial()) ?: return emptyList()
            val nameId = CallDefinitionClause.nameIdentifier(clause) ?: return emptyList()
            val macro = definer.capabilities.compileTime
            return nameArity.arityInterval.closed().map { arity ->
                of(clause.containingFile, nameId, moduleName, nameArity.name, arity, macro)
            }
        }

        /**
         * The symbols [call] declares, whichever form declares a function with a name it spells: a clause, a
         * `defdelegate` or an EEx `function_from_*`. A clause's come from [fromClause], which, unlike a
         * [org.elixir_lang.model.psi.function.FunctionSymbol], also covers a protocol's clauses.
         */
        @RequiresReadLock
        fun fromDeclaration(call: Call): List<AtomSymbol> =
            if (CallableDeclaration.definerOf(call) != null) {
                fromClause(call)
            } else {
                org.elixir_lang.model.psi.function.FunctionSymbol.fromDeclaration(call).map(::of)
            }

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
            return nameArity.arityInterval.closed().map { arity ->
                of(callDefinition.containingFile, callDefinition, moduleName, nameArity.name, arity, macro, presentationText)
            }
        }

        fun matches(symbol: AtomSymbol, moduleName: String, name: String, arity: Int, macro: Boolean): Boolean =
            symbol.moduleName == moduleName &&
                symbol.name == name &&
                symbol.arity == arity &&
                symbol.macro == macro
    }
}
