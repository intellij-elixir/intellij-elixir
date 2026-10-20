package org.elixir_lang.model.psi.callback

import com.intellij.find.usages.api.SearchTarget
import com.intellij.find.usages.api.UsageHandler
import com.intellij.icons.AllIcons
import com.intellij.model.Pointer
import com.intellij.openapi.util.TextRange
import com.intellij.platform.backend.navigation.NavigationRequest
import com.intellij.platform.backend.navigation.NavigationTarget
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiFile
import com.intellij.psi.ResolveState
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.search.SearchScope
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.model.psi.ElixirRenameTarget
import org.elixir_lang.model.psi.RenameClosure
import org.elixir_lang.model.psi.function.FunctionSymbol
import org.elixir_lang.model.psi.function.UseSymbols
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.structure_view.element.CallDefinitionHead
import java.util.*
import org.elixir_lang.declaration.Form
import org.elixir_lang.psi.scope.call_definition_clause.Declarations
import org.elixir_lang.structure_view.element.Callback as CallbackElement

/**
 * Symbol for a single `@callback`/`@macrocallback` name/arity declared by a behaviour module.
 *
 * A behaviour contract (abstract-method-like). Its "usages" are the `def` implementations in
 * modules that `@behaviour` the declaring module (computed semantically).
 *
 * NOTE: this is intentionally a regular class, not a `data class` - [equals]/[hashCode] are by
 * semantic identity `(moduleName, name, arity, macro)` and must NOT include [file]/[range].
 */
@Suppress("UnstableApiUsage")
class Callback(
    override val file: PsiFile,
    override val range: TextRange,
    val moduleName: String,
    val name: String,
    val arity: Int,
    val macro: Boolean
) : ElixirRenameTarget, NavigationTarget, SearchTarget {

    override val searchText: String get() = name
    override val targetName: String get() = name

    override fun createPointer(): Pointer<out Callback> {
        val moduleName = this.moduleName
        val name = this.name
        val arity = this.arity
        val macro = this.macro
        // Anchor to the enclosing `@callback`/`@macrocallback` module attribute (a stable ancestor)
        // rather than to the name-identifier element or a bare file range: an in-place (Shift+F6)
        // rename fully replaces the identifier's text, which swaps out the identifier leaf (collapsing
        // a pointer anchored to it) and collapses a plain range marker to an empty range - either way
        // the subsequent programmatic commit edits the wrong range and applies nothing. The attribute
        // survives the identifier replacement, so its name-identifier range is recomputed on restore.
        val attribute = generateSequence(file.findElementAt(range.startOffset)) { it.parent }
            .filterIsInstance<AtUnqualifiedNoParenthesesCall<*>>()
            .firstOrNull { CallbackElement.`is`(it) && CallbackElement.nameIdentifier(it)?.let(UseSymbols::nameRange) == range }
        if (attribute != null) {
            val attributePointer = SmartPointerManager.getInstance(file.project)
                .createSmartPsiElementPointer(attribute, file)
            return Pointer {
                val restoredAttribute = attributePointer.dereference() ?: return@Pointer null
                val restoredRange = CallbackElement.nameIdentifier(restoredAttribute)?.let(UseSymbols::nameRange)
                    ?: return@Pointer null
                Callback(restoredAttribute.containingFile, restoredRange, moduleName, name, arity, macro)
            }
        }
        return Pointer.fileRangePointer(file, range) { restoredFile, restoredRange ->
            Callback(restoredFile, restoredRange, moduleName, name, arity, macro)
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
        TargetPresentation.builder("$name/$arity")
            .icon(AllIcons.Nodes.AbstractMethod)
            .presentation()

    @RequiresReadLock
    override fun renameClosure(): RenameClosure = FunctionSymbol.renameClosure(this)

    override fun equals(other: Any?): Boolean =
        other is Callback &&
                other.moduleName == moduleName &&
                other.name == name &&
                other.arity == arity &&
                other.macro == macro

    override fun hashCode(): Int = Objects.hash(moduleName, name, arity, macro)

    override fun toString(): String = "Callback($moduleName.$name/$arity, macro=$macro)"

    companion object {
        /**
         * Build the [Callback] symbol(s) declared by a `@callback foo(...) :: ...` module attribute.
         *
         * Returns a LIST: a head with default arguments declares several arities
         * (`@callback foo(a, b \\ 1)` -> `foo/1` AND `foo/2`), so one [Callback] is emitted per arity.
         */
        @RequiresReadLock
        fun fromModuleAttribute(attr: AtUnqualifiedNoParenthesesCall<*>): List<Callback> {
            if (!CallbackElement.`is`(attr)) return emptyList()
            // The walk's declaration, which a head with no atom value, such as `unquote(name)`, does not have.
            if (Declarations.of(Form.CALLBACK, attr, ResolveState.initial()).none { it.declaration != null }) return emptyList()
            val head = CallbackElement.headCall(attr) ?: return emptyList()
            val nameArity = CallDefinitionHead.nameArityInterval(head, ResolveState.initial()) ?: return emptyList()
            val nameRange = CallbackElement.nameIdentifier(attr)?.let(UseSymbols::nameRange) ?: return emptyList()
            val modular = org.elixir_lang.psi.CallDefinitionClause.enclosingModularMacroCall(attr) ?: return emptyList()
            val moduleName = BehaviourMembership.moduleName(modular) ?: return emptyList()
            val macro = org.elixir_lang.psi.impl.ElixirPsiImplUtil.moduleAttributeName(attr) == "@macrocallback"

            return nameArity.arityInterval.closed().map { arity ->
                Callback(attr.containingFile, nameRange, moduleName, nameArity.name, arity, macro)
            }
        }
    }
}
