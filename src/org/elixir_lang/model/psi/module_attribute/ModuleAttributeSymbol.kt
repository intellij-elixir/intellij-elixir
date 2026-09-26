package org.elixir_lang.model.psi.module_attribute

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
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.search.SearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.model.psi.ElixirSymbolWithUsages
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.ElixirAtIdentifier
import org.elixir_lang.psi.ModuleAttribute
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.declarations.UseScopeImpl
import org.elixir_lang.psi.impl.identifierName
import org.elixir_lang.psi.impl.identifierTextRange
import java.util.*

@Suppress("UnstableApiUsage")
class ModuleAttributeSymbol(
    override val file: PsiFile,
    override val range: TextRange,
    val moduleName: String,
    val name: String
) : ElixirSymbolWithUsages, NavigationTarget, SearchTarget {
    override val searchText: String get() = name
    override val targetName: String get() = name

    override fun createPointer(): Pointer<out ModuleAttributeSymbol> {
        val moduleName = this.moduleName
        val name = this.name
        // Anchor to the declaring PSI element rather than a bare file range: an in-place (Shift+F6)
        // rename fully replaces the identifier's text, which collapses a plain range marker to an
        // empty range and corrupts the subsequent programmatic commit. A smart element pointer
        // re-anchors by PSI structure, so the identifier range is recomputed correctly on restore.
        val declaration = declaration()
        if (declaration != null) {
            val declarationPointer =
                SmartPointerManager.getInstance(file.project).createSmartPsiElementPointer(declaration, file)
            return Pointer {
                val restoredDeclaration = declarationPointer.dereference() ?: return@Pointer null
                ModuleAttributeSymbol(
                    restoredDeclaration.containingFile,
                    restoredDeclaration.atIdentifier.identifierTextRange(),
                    moduleName,
                    name
                )
            }
        }
        return Pointer.fileRangePointer(file, range) { restoredFile, restoredRange ->
            ModuleAttributeSymbol(restoredFile, restoredRange, moduleName, name)
        }
    }

    override fun computePresentation(): TargetPresentation = presentation()

    override fun navigationRequest(): NavigationRequest? =
        NavigationRequest.sourceNavigationRequest(file, range)

    override val maximalSearchScope: SearchScope?
        @RequiresReadLock get() = declaration()?.let { UseScopeImpl.get(it) }

    override val usageHandler: UsageHandler
        get() = UsageHandler.createEmptyUsageHandler("@$name")

    override fun presentation(): TargetPresentation =
        TargetPresentation.builder("@$name")
            .icon(AllIcons.Nodes.Property)
            .containerText(moduleName)
            .presentation()

    override fun equals(other: Any?): Boolean =
        other is ModuleAttributeSymbol &&
            other.name == name &&
            other.moduleName == moduleName &&
            other.file.virtualFile == file.virtualFile &&
            other.range == range

    override fun hashCode(): Int = Objects.hash(name, moduleName, file.virtualFile, range)

    override fun toString(): String = "ModuleAttributeSymbol($moduleName.@$name, $range)"

    @RequiresReadLock
    private fun declaration(): AtUnqualifiedNoParenthesesCall<*>? =
        generateSequence(file.findElementAt(range.startOffset)) { it.parent }
            .filterIsInstance<AtUnqualifiedNoParenthesesCall<*>>()
            .firstOrNull { declaration ->
                declaration.atIdentifier.identifierTextRange() == range
            }

    companion object {
        @RequiresReadLock
        fun fromDeclaration(call: AtUnqualifiedNoParenthesesCall<*>): ModuleAttributeSymbol? {
            if (!ModuleAttribute.isDeclaration(call)) return null
            val atIdentifier = call.atIdentifier
            if (ModuleAttribute.isNonReferencing(atIdentifier)) return null
            val name = atIdentifier.identifierName()
            // An attribute declared inside a `quote` block is enclosed by the `quote` call, which is
            // not a modular and has no name, so walk outward until one that does name a module is
            // found. Without this the declaration resolves but no symbol is ever built for it.
            val moduleName = generateSequence(CallDefinitionClause.enclosingModularMacroCall(call)) {
                CallDefinitionClause.enclosingModularMacroCall(it)
            }
                .firstNotNullOfOrNull { modular ->
                    runCatching { org.elixir_lang.psi.Module.name(modular) }
                        .getOrElse { if (it is ProcessCanceledException) throw it else null }
                }
                ?: return null
            return ModuleAttributeSymbol(call.containingFile, atIdentifier.identifierTextRange(), moduleName, name)
        }

        @RequiresReadLock
        fun nameIdentifierElement(element: PsiElement): PsiElement? =
            when (element) {
                is AtUnqualifiedNoParenthesesCall<*> -> element.atIdentifier
                is ElixirAtIdentifier -> element
                is Call -> element.functionNameElement()
                else -> null
            }

        @RequiresReadLock
        fun isHead(element: PsiElement): Boolean {
            val declaration = when (element) {
                is AtUnqualifiedNoParenthesesCall<*> -> element
                else -> generateSequence(element) { it.parent }
                    .filterIsInstance<AtUnqualifiedNoParenthesesCall<*>>()
                    .firstOrNull()
            } ?: return false
            if (fromDeclaration(declaration) == null) return false
            return element == declaration || PsiTreeUtil.isAncestor(declaration.atIdentifier, element, false)
        }
    }
}
