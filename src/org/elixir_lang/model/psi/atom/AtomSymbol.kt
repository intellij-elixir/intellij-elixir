package org.elixir_lang.model.psi.atom

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
import com.intellij.psi.ResolveState
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.search.SearchScope
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.model.psi.ElixirRenameTarget
import org.elixir_lang.model.psi.ElixirSymbolWithUsages
import org.elixir_lang.model.psi.function.FunctionSymbol
import org.elixir_lang.navigation.DeclarationHead
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.call.Call
import java.util.*
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition

@Suppress("UnstableApiUsage")
class AtomSymbol(
    override val file: PsiFile,
    override val range: TextRange,
    val moduleName: String,
    val name: String,
    val arity: Int,
    val macro: Boolean,
    private val displayText: String? = null,
    /** As [FunctionSymbol.injected]; the range already tells it apart. */
    val injected: Boolean = false
) : ElixirRenameTarget, NavigationTarget, SearchTarget {
    override val searchText: String get() = name
    override val targetName: String get() = name

    override fun createPointer(): Pointer<out AtomSymbol> {
        val moduleName = this.moduleName
        val name = this.name
        val arity = this.arity
        val macro = this.macro
        val displayText = this.displayText
        val injected = this.injected
        // Anchor to the enclosing call-definition clause (a stable ancestor) rather than to the
        // name-identifier element or a bare file range: an in-place (Shift+F6) rename fully replaces
        // the identifier's text, which swaps out the identifier leaf (collapsing a pointer anchored to
        // it) and collapses a plain range marker to an empty range - either way the subsequent
        // programmatic commit edits the wrong range and applies nothing. The clause survives the
        // identifier replacement, so its name-identifier range is recomputed correctly on restore.
        // (Beam-decompiled atoms have no source clause and fall through to the range pointer.)
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
                val restoredFile = restoredClause.containingFile

                AtomSymbol(restoredFile, restoredRange, moduleName, name, arity, macro, displayText, injected)
            }
        }
        return Pointer.fileRangePointer(file, range) { restoredFile, restoredRange ->
            AtomSymbol(restoredFile, restoredRange, moduleName, name, arity, macro, displayText, injected)
        }
    }

    override fun computePresentation(): TargetPresentation = presentation()

    override fun navigationRequest(): NavigationRequest? =
        NavigationRequest.sourceNavigationRequest(file, range)

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
        displayText ?: (declarationElement() as? Call)?.let { clause ->
            CallDefinitionClause.declaration(clause, ResolveState.initial())?.let { DeclarationHead.text(it, clause) }
        }

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

    /** The function this atom names, the same symbol for every use query and for rename. */
    fun functionSymbol(): FunctionSymbol = FunctionSymbol(file, range, moduleName, name, arity, macro, injected)

    @RequiresReadLock
    override fun renamedWith(): List<ElixirSymbolWithUsages> = functionSymbol().renamedWith()

    @get:RequiresReadLock
    override val declaringMacro: String?
        get() = functionSymbol().declaringMacro

    @RequiresReadLock
    fun declarationElement(): PsiElement? =
        generateSequence(file.findElementAt(range.startOffset)) { it.parent }
            .filterIsInstance<Call>()
            .firstOrNull { CallDefinitionClause.`is`(it) }

    companion object {
        @RequiresReadLock
        fun fromClause(clause: Call): List<AtomSymbol> {
            if (!CallDefinitionClause.`is`(clause)) return emptyList()
            val enclosingModular = CallDefinitionClause.enclosingModularMacroCall(clause) ?: return emptyList()
            val owner = FunctionSymbol.owner(enclosingModular) ?: return emptyList()
            val nameArity = CallDefinitionClause.nameArityInterval(clause, ResolveState.initial()) ?: return emptyList()
            val nameId = CallDefinitionClause.nameIdentifier(clause) ?: return emptyList()
            val macro = CallDefinitionClause.capabilities(clause)?.let(FunctionSymbol::isMacro) == true
            return nameArity.arityInterval.closed().map { arity ->
                AtomSymbol(
                    clause.containingFile,
                    nameId.textRange,
                    owner.moduleName,
                    nameArity.name,
                    arity,
                    macro,
                    injected = owner.injected
                )
            }
        }

        @RequiresReadLock
        fun fromBeamCallDefinition(callDefinition: BeamCallDefinition): List<AtomSymbol> {
            val navigationClause = callDefinition.navigationElement as? Call

            if (navigationClause != null && CallDefinitionClause.capabilities(navigationClause)?.quotesArguments == true) return emptyList()
            if (navigationClause == null && callDefinition.capabilities.compileTime) return emptyList()

            val presentationText =
                if (navigationClause != null && CallDefinitionClause.`is`(navigationClause)) {
                    DeclarationHead.text(callDefinition.declaration(), navigationClause)
                } else {
                    null
                }
            val moduleName = callDefinition.parent.name
            val nameArity = callDefinition.nameArityInterval
            return nameArity.arityInterval.closed().map { arity ->
                AtomSymbol(
                    callDefinition.containingFile,
                    callDefinition.textRange,
                    moduleName,
                    nameArity.name,
                    arity,
                    false,
                    presentationText
                )
            }
        }

        fun matches(symbol: AtomSymbol, moduleName: String, name: String, arity: Int, macro: Boolean): Boolean =
            symbol.moduleName == moduleName &&
                symbol.name == name &&
                symbol.arity == arity &&
                symbol.macro == macro
    }
}
