package org.elixir_lang.model.psi.type

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
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.model.psi.ElixirSymbolWithUsages
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.ElixirKeywordKey
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.scope.ancestorTypeSpec
import org.elixir_lang.psi.scope.isTypeRestriction
import org.elixir_lang.structure_view.element.CallDefinitionSpecification
import org.elixir_lang.structure_view.element.Type as TypeElement
import java.util.*

/**
 * A type variable declared in a `@type`/`@typep`/`@opaque` head (e.g. `a` in `@type box(a) :: {:box, a}`)
 * or bound by a `@spec`/`@callback ... when` clause (e.g. `a` in `@spec swap({a, b}) :: {b, a} when a: term()`).
 *
 * File-local and arity-less - unlike [TypeSymbol], it has no module-qualified name and no `@type` attribute
 * of its own. Its [range] points at the declaring occurrence (the head parameter, or the `when` keyword key),
 * so a usage in the body navigates back to it, and Ctrl-Click on the declaration itself shows its usages.
 * Usages are searched only within the enclosing `@type`/`@spec` attribute ([maximalSearchScope]).
 */
@Suppress("UnstableApiUsage")
class TypeVariableSymbol(
    override val file: PsiFile,
    override val range: TextRange,
    val name: String
) : ElixirSymbolWithUsages, NavigationTarget, SearchTarget {
    override val searchText: String get() = name
    override val targetName: String get() = name

    override fun createPointer(): Pointer<out TypeVariableSymbol> {
        val name = this.name
        // Anchor to the enclosing `@type`/`@spec` attribute (a large, structurally stable ancestor) and
        // recompute the occurrence by its offset within that attribute. An in-place (Shift+F6) rename
        // replaces the identifier leaf, which would invalidate a pointer anchored to the leaf/marker; the
        // enclosing attribute survives, so the range is recomputed from the restored attribute.
        val attribute = enclosingTypeSpecAttribute()
        if (attribute != null && attribute.textRange.contains(range)) {
            val attributePointer = SmartPointerManager.getInstance(file.project)
                .createSmartPsiElementPointer(attribute, file)
            val relativeStart = range.startOffset - attribute.textRange.startOffset
            val length = range.length
            return Pointer {
                val restored = attributePointer.dereference() ?: return@Pointer null
                val start = restored.textRange.startOffset + relativeStart
                if (start + length > restored.textRange.endOffset) return@Pointer null
                TypeVariableSymbol(restored.containingFile, TextRange(start, start + length), name)
            }
        }
        return Pointer.fileRangePointer(file, range) { restoredFile, restoredRange ->
            TypeVariableSymbol(restoredFile, restoredRange, name)
        }
    }

    override fun computePresentation(): TargetPresentation = presentation()

    override fun navigationRequest(): NavigationRequest? =
        NavigationRequest.sourceNavigationRequest(file, range)

    override val maximalSearchScope: SearchScope?
        @RequiresReadLock get() = enclosingTypeSpecAttribute()?.let { LocalSearchScope(it) }

    /**
     * The enclosing `@type`/`@spec`/`@callback` attribute of this variable's declaring occurrence. Walks up
     * from the range's leaf to the first type-spec [AtUnqualifiedNoParenthesesCall]; [ancestorTypeSpec] is
     * only consulted on that call (it has no case for raw leaves or keyword keys).
     */
    @RequiresReadLock
    private fun enclosingTypeSpecAttribute(): AtUnqualifiedNoParenthesesCall<*>? =
        file.findElementAt(range.startOffset)?.let { leaf ->
            generateSequence(leaf) { it.parent }
                .filterIsInstance<AtUnqualifiedNoParenthesesCall<*>>()
                .firstOrNull { it.ancestorTypeSpec() != null }
        }

    override val usageHandler: UsageHandler
        get() = UsageHandler.createEmptyUsageHandler(name)

    override fun presentation(): TargetPresentation =
        TargetPresentation.builder(name)
            .icon(AllIcons.Nodes.Variable)
            .containerText("type variable")
            .presentation()

    override fun equals(other: Any?): Boolean =
        other is TypeVariableSymbol &&
            other.name == name &&
            other.file.virtualFile == file.virtualFile &&
            other.range == range

    override fun hashCode(): Int = Objects.hash(name, file.virtualFile, range)

    override fun toString(): String = "TypeVariableSymbol($name, $range)"

    companion object {
        @RequiresReadLock
        fun fromDeclaration(element: PsiElement): TypeVariableSymbol? {
            if (!isDeclaration(element)) return null
            val nameElement = nameIdentifierElement(element) ?: return null
            val name = variableName(element) ?: return null

            return TypeVariableSymbol(element.containingFile, nameElement.textRange, name)
        }

        /**
         * True when [element] is a *declaring* occurrence of a type variable: a head parameter of a
         * `@type`/`@typep`/`@opaque` (heads bind variables), or a `when key: type` keyword key. A `@spec`
         * head parameter is a *usage*, not a declaration - specs bind their variables in the `when` clause.
         */
        @RequiresReadLock
        fun isDeclaration(element: PsiElement): Boolean =
            when (element) {
                is ElixirKeywordKey -> element.parent?.isTypeRestriction() == true
                is Call -> isTypeHeadParameterDeclaration(element)
                else -> false
            }

        @RequiresReadLock
        private fun isTypeHeadParameterDeclaration(call: Call): Boolean {
            val attribute = call.ancestorTypeSpec() ?: return false
            if (!TypeElement.`is`(attribute)) return false
            val specification = CallDefinitionSpecification.specification(attribute) ?: return false
            val head = CallDefinitionSpecification.specificationType(specification) ?: return false
            val parameters = head.finalArguments() ?: return false

            return parameters.any { it === call || it.isEquivalentTo(call) }
        }

        @RequiresReadLock
        fun nameIdentifierElement(element: PsiElement): PsiElement? =
            when (element) {
                is Call -> element.functionNameElement()
                is ElixirKeywordKey -> element
                else -> null
            }

        @RequiresReadLock
        fun variableName(element: PsiElement): String? =
            when (element) {
                is Call -> element.functionName()
                is ElixirKeywordKey -> element.text
                else -> null
            }
    }
}
