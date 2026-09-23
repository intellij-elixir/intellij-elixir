package org.elixir_lang.model.psi.type

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
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.search.SearchScope
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.model.psi.ElixirSymbolWithUsages
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.call.Call
import org.elixir_lang.structure_view.element.CallDefinitionSpecification
import org.elixir_lang.structure_view.element.Type as TypeElement
import java.util.*

@Suppress("UnstableApiUsage")
class TypeSymbol(
    override val file: PsiFile,
    override val range: TextRange,
    val moduleName: String,
    val name: String,
    val arity: Int
) : ElixirSymbolWithUsages, NavigationTarget, SearchTarget {
    override val searchText: String get() = name
    override val targetName: String get() = name

    override fun createPointer(): Pointer<out TypeSymbol> {
        val moduleName = this.moduleName
        val name = this.name
        val arity = this.arity
        // Anchor to the enclosing `@type`/`@typep`/`@opaque` module attribute (a stable ancestor)
        // rather than to the name-identifier element or a bare file range: an in-place (Shift+F6)
        // rename fully replaces the identifier's text, which swaps out the identifier leaf (collapsing
        // a pointer anchored to it) and collapses a plain range marker to an empty range - either way
        // the subsequent programmatic commit edits the wrong range and applies nothing. The attribute
        // survives the identifier replacement, so its name-identifier range is recomputed on restore.
        val attribute = generateSequence(file.findElementAt(range.startOffset)) { it.parent }
            .filterIsInstance<org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall<*>>()
            .firstOrNull { TypeElement.`is`(it) && TypeElement.nameIdentifier(it)?.textRange == range }
        if (attribute != null) {
            val attributePointer = SmartPointerManager.getInstance(file.project)
                .createSmartPsiElementPointer(attribute, file)
            return Pointer {
                val restoredAttribute = attributePointer.dereference() ?: return@Pointer null
                val restoredRange = TypeElement.nameIdentifier(restoredAttribute)?.textRange
                    ?: return@Pointer null
                TypeSymbol(restoredAttribute.containingFile, restoredRange, moduleName, name, arity)
            }
        }
        return Pointer.fileRangePointer(file, range) { restoredFile, restoredRange ->
            TypeSymbol(restoredFile, restoredRange, moduleName, name, arity)
        }
    }

    override fun computePresentation(): TargetPresentation = presentation()

    override fun navigationRequest(): NavigationRequest? =
        NavigationRequest.sourceNavigationRequest(file, range)

    override val maximalSearchScope: SearchScope? get() = null

    override val usageHandler: UsageHandler
        get() = UsageHandler.createEmptyUsageHandler("$name/$arity")

    override fun presentation(): TargetPresentation =
        TargetPresentation.builder("$moduleName.$name/$arity")
            .icon(AllIcons.Nodes.Type)
            .presentation()

    override fun equals(other: Any?): Boolean =
        other is TypeSymbol &&
            other.moduleName == moduleName &&
            other.name == name &&
            other.arity == arity

    override fun hashCode(): Int = Objects.hash(moduleName, name, arity)

    override fun toString(): String = "TypeSymbol($moduleName.$name/$arity)"

    companion object {
        @RequiresReadLock
        fun fromTypeAttribute(typeAttribute: Call): List<TypeSymbol> {
            if (!TypeElement.`is`(typeAttribute)) return emptyList()
            val typeAttributeCall = typeAttribute as? org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall<*> ?: return emptyList()
            val specification = TypeElement.specification(typeAttributeCall) ?: return emptyList()
            val typeHead = CallDefinitionSpecification.specificationType(specification) ?: return emptyList()
            val name = typeHead.functionName() ?: return emptyList()
            val arity = typeHead.resolvedFinalArity()
            val nameId = TypeElement.nameIdentifier(typeAttributeCall) ?: return emptyList()
            val enclosingModular = CallDefinitionClause.enclosingModularMacroCall(typeAttributeCall) ?: return emptyList()
            val moduleName = runCatching { org.elixir_lang.psi.Module.name(enclosingModular) }
                .getOrElse { if (it is ProcessCanceledException) throw it else null }
                ?: return emptyList()

            // For a decompiled BEAM type, this `@type` Call lives in an in-memory mirror file built from the
            // `.beam`'s decompiled text; its `originalFile` is the navigable compiled file whose virtual file opens
            // the decompiled editor at the very offsets used here (mirror text == decompiled document text). For a
            // source type `originalFile` is the file itself, so this is a no-op there.
            val declarationFile = typeAttributeCall.containingFile.originalFile

            return listOf(TypeSymbol(declarationFile, nameId.textRange, moduleName, name, arity))
        }
    }
}
