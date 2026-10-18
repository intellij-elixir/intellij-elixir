package org.elixir_lang.model.psi.function

import com.intellij.find.usages.api.SearchTarget
import com.intellij.find.usages.api.UsageHandler
import com.intellij.icons.AllIcons
import com.intellij.model.Pointer
import com.intellij.navigation.NavigatableSymbol
import com.intellij.openapi.project.Project
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
import org.elixir_lang.declaration.Form
import org.elixir_lang.declaration.Presentation
import org.elixir_lang.model.psi.ElixirRenameTarget
import org.elixir_lang.model.psi.ElixirSymbolWithUsages
import org.elixir_lang.model.psi.callback.CallbackImplementation
import org.elixir_lang.model.psi.protocol.ProtocolImplementation
import org.elixir_lang.navigation.DeclarationHead
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.DefinitionDeclaration
import org.elixir_lang.psi.Implementation
import org.elixir_lang.psi.Protocol
import org.elixir_lang.psi.arityInterval
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.SyntacticCall
import org.elixir_lang.psi.impl.call.CanonicallyNamedImpl
import org.elixir_lang.psi.scope.call_definition_clause.DeclaringForm
import org.elixir_lang.psi.scope.call_definition_clause.Declarations
import org.elixir_lang.psi.stub.type.call.Stub
import org.elixir_lang.structure_view.element.Delegation
import java.util.*

/**
 * Symbol representing a single `def`/`defp`/`defmacro`/`defmacrop`/`defguard`/`defguardp` clause
 * in a regular module (not inside a `defprotocol` - those are `ProtocolFunction`).
 *
 * "Usages" are the call sites that invoke it, computed by `ElixirSymbolUsageSearcher`.
 *
 * NOTE: intentionally a regular class, not a `data class` - [equals]/[hashCode] are by semantic
 * identity and must NOT include [file]/[range].
 */
@Suppress("UnstableApiUsage")
class FunctionSymbol(
    override val file: PsiFile,
    override val range: TextRange,
    val moduleName: String,
    val name: String,
    val arity: Int,
    val macro: Boolean,
    /** Written in a `quote` that [moduleName] injects, so it is no definition of [moduleName]'s own. */
    val injected: Boolean = false,
    /** Made from a definition whose arity can't be known yet, so at [arity] as at any other. */
    val ofUnknownArity: Boolean = false
) : ElixirRenameTarget, NavigatableSymbol, SearchTarget {

    override val searchText: String get() = name
    override val targetName: String get() = name

    override val declarationTextByName: ((String) -> String)?
        get() = UseSymbols.nameTextAt(file, range)

    override fun createPointer(): Pointer<out FunctionSymbol> {
        val moduleName = this.moduleName
        val name = this.name
        val arity = this.arity
        val macro = this.macro
        val injected = this.injected
        val ofUnknownArity = this.ofUnknownArity
        // Anchor to the declaring call (a stable ancestor) rather than to the name element or a bare file range: an
        // in-place (Shift+F6) rename fully replaces the name's text, which swaps out the name leaf (collapsing a
        // pointer anchored to it) and collapses a plain range marker to an empty range - either way the subsequent
        // programmatic commit edits the wrong range and applies nothing. The declaring call survives the name's
        // replacement, so its name's range is recomputed correctly on restore.
        val anchor = anchor()
        if (anchor != null) {
            val (call, form) = anchor
            val callPointer = SmartPointerManager.getInstance(file.project).createSmartPsiElementPointer(call, file)
            return Pointer {
                val restored = callPointer.dereference() ?: return@Pointer null
                val restoredName = DeclaringForm.names(restored, form).singleOrNull() ?: return@Pointer null
                val restoredRange = UseSymbols.nameRange(restoredName)

                FunctionSymbol(restored.containingFile, restoredRange, moduleName, name, arity, macro, injected, ofUnknownArity)
            }
        }
        return Pointer.fileRangePointer(file, range) { restoredFile, restoredRange ->
            FunctionSymbol(restoredFile, restoredRange, moduleName, name, arity, macro, injected, ofUnknownArity)
        }
    }

    // --- NavigatableSymbol ---
    /** A delegation's Go To lands on every declaration of what it delegates to, or on the delegation when that is nothing. */
    @RequiresReadLock
    override fun getNavigationTargets(project: Project): Collection<NavigationTarget> =
        anchor()
            ?.takeIf { it.form == Form.DELEGATION }
            ?.let { UseSymbols.delegatedTo(it.call) }
            // A target that is a delegation too is landed on, not followed.
            ?.mapNotNull { target -> (target as? FunctionSymbol)?.let(::Place) ?: target as? NavigationTarget }
            ?.ifEmpty { null }
            ?: listOf(Place(this))

    /** Where [symbol] is declared, as a row of Go To. */
    private class Place(private val symbol: FunctionSymbol) : NavigationTarget {
        override fun createPointer(): Pointer<out NavigationTarget> = Pointer.delegatingPointer(symbol.createPointer(), ::Place)

        override fun computePresentation(): TargetPresentation = symbol.presentation()

        override fun navigationRequest(): NavigationRequest? = NavigationRequest.sourceNavigationRequest(symbol.file, symbol.range)
    }

    // --- SearchTarget ---
    override val maximalSearchScope: SearchScope? get() = null

    override val usageHandler: UsageHandler
        get() = UsageHandler.createEmptyUsageHandler("$name/$arity")

    override fun presentation(): TargetPresentation =
        TargetPresentation.builder(headText() ?: "$moduleName.$name/$arity")
            .containerText(moduleName)
            .icon(if (macro) AllIcons.Nodes.AbstractMethod else AllIcons.Nodes.Method)
            .presentation()

    /** The declaring call whose name this Symbol's range is. */
    @RequiresReadLock
    private fun anchor(): DeclaringForm.Declaring? =
        file.findElementAt(range.startOffset)
            ?.let(DeclaringForm::named)
            ?.takeIf { (call, form) -> DeclaringForm.names(call, form).singleOrNull()?.let(UseSymbols::nameRange) == range }

    @RequiresReadLock
    private fun headText(): String? {
        val (call, form) = anchor() ?: return null
        val declaration = Declarations.named(form, call, name) ?: return null

        return DeclarationHead.text(declaration, call)
    }

    /**
     * The callbacks the definition this symbol is made from, a clause or a `defdelegate`, implements, and the protocol
     * functions each protocol `def` it implements declares.
     */
    @RequiresReadLock
    fun implementedMembers(): List<ElixirSymbolWithUsages> =
        madeFromDefinition(file, range)
            ?.let { CallbackImplementation.implementedCallbacks(it) + ProtocolImplementation.membersOfImplementedDefs(it) }
            .orEmpty()

    /**
     * What a rename of this Symbol also renames: what it implements, and for a delegation without `as:`, whose head
     * names its targets too, those targets, each with what it implements and delegates to in turn.
     */
    @RequiresReadLock
    override fun renamedWith(): List<ElixirSymbolWithUsages> =
        (reachable<ElixirSymbolWithUsages>(this) { (it as? FunctionSymbol)?.renamedDirectlyWith().orEmpty() } - this).toList()

    @RequiresReadLock
    private fun renamedDirectlyWith(): List<ElixirSymbolWithUsages> = implementedMembers() + namedByHead()

    /** The Symbols the head this Symbol is declared by names besides it: [UseSymbols.namedByHead]. */
    @RequiresReadLock
    fun namedByHead(): List<ElixirSymbolWithUsages> =
        anchor()?.call?.let(UseSymbols::namedByHead).orEmpty().filterIsInstance<ElixirSymbolWithUsages>()

    /**
     * A delegation without `as:` calls its target by its head's name, so renaming the head without the target breaks it
     * when the target is not found, and `as:` can only be added to options that are a keyword list.
     */
    @RequiresReadLock
    override fun delegationRefusal(): String? =
        anchor()
            ?.call
            ?.takeIf { Delegation.headKeepsTargetName(it) }
            ?.let { call ->
                if (!Delegation.optionsAreKeywordList(call)) {
                    "its options are not a keyword list, so `as:` cannot be added"
                } else {
                    UseSymbols.unfoundTarget(call)?.let { "its target $it is not found" }
                }
            }

    /**
     * An embed's or `defexception`'s call does not write the function's name, an EEx function's calls through an
     * `import` and its `only:` keys are not found as its uses, and a function a `quote` injects is called in modules
     * not known until it is used, so a rename of what they declare would not rewrite it whole.
     */
    @get:RequiresReadLock
    override val declaringMacro: String?
        get() = if (injected) "quote" else file.findElementAt(range.startOffset)
            ?.let(DeclaringForm::enclosing)
            ?.takeIf { (_, form) ->
                when (form) {
                    Form.EEX_FUNCTION_FROM, Form.GENERATOR_EMBED, Form.EXCEPTION -> true
                    Form.CLAUSE, Form.DELEGATION, Form.CALLBACK -> false
                }
            }
            ?.call
            ?.functionName()

    override fun equals(other: Any?): Boolean =
        other is FunctionSymbol &&
            other.moduleName == moduleName &&
            other.name == name &&
            other.arity == arity &&
            other.macro == macro &&
            other.injected == injected &&
            other.ofUnknownArity == ofUnknownArity

    override fun hashCode(): Int = Objects.hash(moduleName, name, arity, macro, injected, ofUnknownArity)

    override fun toString(): String =
        "FunctionSymbol($moduleName.$name/$arity, macro=$macro${if (injected) ", injected" else ""}" +
            "${if (ofUnknownArity) ", of unknown arity" else ""})"

    /** The module a definition keys to, [injected] when the definition is written in a `quote` inside it. */
    data class Owner(val moduleName: String, val injected: Boolean)

    companion object {
        /** Whether a declaration with [capabilities] is presented as a macro: a guard is, as it is expanded at compile time. */
        fun isMacro(capabilities: Capabilities): Boolean =
            when (capabilities.presentation) {
                Presentation.FUNCTION -> false
                Presentation.MACRO, Presentation.GUARD -> true
            }

        /**
         * The module a function defined directly in [modular] keys to: a `defmodule`'s or `defprotocol`'s canonical
         * name, nested in the modules around it, and a `defimpl`'s protocol, so every implementation of a function is
         * one. A module whose name is computed, `defmodule unquote(name)`, is named by its first argument's text. In a
         * `quote`, the module around it, marked injected: the modules the `quote` lands in are not known here.
         */
        @RequiresReadLock
        fun owner(modular: Call): Owner? {
            val keyed = generateSequence(modular) { CallDefinitionClause.enclosingModularMacroCall(it) }
                .firstOrNull { Stub.isModular(it) }
                ?: return null
            val moduleName =
                if (Implementation.`is`(keyed)) {
                    Implementation.protocolName(keyed)
                } else {
                    val syntactic = SyntacticCall.of(keyed)

                    CanonicallyNamedImpl.canonicalNameSet(syntactic)
                        .firstOrNull()
                        ?.takeIf { org.elixir_lang.Module.atom(it) != null }
                        ?: org.elixir_lang.psi.Module.moduleName(syntactic)?.name
                        ?: syntactic.firstPrimaryArgumentText()
                }

            return moduleName?.let { Owner(it, injected = keyed != modular) }
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
            val owner = owner(enclosingModular) ?: return emptyList()
            val nameArity = CallDefinitionClause.nameArityInterval(clause, ResolveState.initial()) ?: return emptyList()
            val nameId = CallDefinitionClause.nameIdentifier(clause) ?: return emptyList()
            val macro = CallDefinitionClause.capabilities(clause)?.let(::isMacro) == true
            // For a decompiled beam function, this clause lives in an in-memory mirror file built from the `.beam`'s
            // decompiled text; its `originalFile` is the navigable compiled file whose virtual file opens the
            // decompiled editor at these offsets. For a source function `originalFile` is the file itself (no-op).
            val declarationFile = clause.containingFile.originalFile
            return nameArity.arityInterval.closed().map { arity ->
                FunctionSymbol(
                    declarationFile,
                    UseSymbols.nameRange(nameId),
                    owner.moduleName,
                    nameArity.name,
                    arity,
                    macro,
                    owner.injected
                )
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
         * A definition of any form is a use of a symbol it has at that symbol's arity ([UseSymbols.symbols]), except in
         * a `defimpl`. There every implementation of a function has one symbol, the protocol's, so a definition that
         * implements no protocol function, such as a helper, is a use of [symbol] only when [symbol] is made from a
         * clause of the same function in the same `defimpl`. A `defdelegate` is a use of the symbol made from it alone,
         * found by its file and head range, since a sibling `defimpl`'s `defdelegate` has an equal symbol; and never at
         * its own head, which its declaration already gives.
         */
        @RequiresReadLock
        fun matches(definition: PsiElement, symbol: FunctionSymbol, at: PsiElement): Boolean {
            if (symbol !in UseSymbols.symbols(definition, symbol.name, symbol.arity)) return false

            val call = definitionClause(definition) ?: definition as? Call ?: return true
            if (!Delegation.`is`(call)) return isInItsImplementation(call, symbol)

            val head = Delegation.nameIdentifier(call) ?: return false
            val atOwnHead = PsiTreeUtil.isAncestor(head, at, false) || PsiTreeUtil.isAncestor(at, head, false)

            return !atOwnHead && isMadeFrom(symbol, call, head)
        }

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

            if (owner(enclosingModular) != Owner(symbol.moduleName, symbol.injected)) return false

            val nameArity = CallDefinitionClause.nameArityInterval(clause, ResolveState.initial()) ?: return false

            return nameArity.name == symbol.name &&
                symbol.arity in nameArity.arityInterval &&
                (CallDefinitionClause.capabilities(clause)?.let(::isMacro) == true) == symbol.macro
        }

        @RequiresReadLock
        private fun matchesClause(clause: Call, symbol: FunctionSymbol): Boolean =
            symbol in fromClause(clause) && isInItsImplementation(clause, symbol)

        /** Whether [definition], one of [symbol]'s, is not in a `defimpl`, or is in one where [matches] counts it as [symbol]'s. */
        @RequiresReadLock
        private fun isInItsImplementation(definition: Call, symbol: FunctionSymbol): Boolean {
            val enclosingModular = CallDefinitionClause.enclosingModularMacroCall(definition) ?: return false
            if (!Implementation.`is`(enclosingModular)) return true

            helperImplementation(symbol)?.let { return it == enclosingModular }

            return implements(definition, symbol)
        }

        /**
         * The `defimpl` of the definition [symbol] is made from when that definition implements no protocol function,
         * whether a `defp` or a public `def` the protocol does not declare, of any form but a `defdelegate`, else `null`.
         */
        @RequiresReadLock
        private fun helperImplementation(symbol: FunctionSymbol): Call? {
            val definition = symbol.anchor()?.takeUnless { it.form == Form.DELEGATION }?.call ?: return null
            val enclosingModular = CallDefinitionClause.enclosingModularMacroCall(definition)?.takeIf { Implementation.`is`(it) }
                ?: return null

            return enclosingModular.takeUnless { implements(definition, symbol) }
        }

        /** Whether [definition], in a `defimpl`, implements the protocol function [symbol] is named by. */
        @RequiresReadLock
        private fun implements(definition: Call, symbol: FunctionSymbol): Boolean =
            ProtocolImplementation.implementsMember(definition, symbol.name, symbol.arity, symbol.macro)

        /** The clause or `defdelegate` whose name identifier is [range] in [file]. */
        @RequiresReadLock
        private fun madeFromDefinition(file: PsiFile, range: TextRange): Call? =
            generateSequence(file.findElementAt(range.startOffset)) { it.parent }
                .filterIsInstance<Call>()
                .firstOrNull { DefinitionDeclaration.`is`(it) && DefinitionDeclaration.nameIdentifier(it)?.let(UseSymbols::nameRange) == range }

        private fun isMadeFrom(symbol: FunctionSymbol, definition: Call, head: PsiElement): Boolean =
            definition.containingFile.originalFile.virtualFile == symbol.file.virtualFile &&
                UseSymbols.nameRange(head) == symbol.range

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
            val owner = owner(enclosingModular) ?: return emptyList()
            val arityInterval = declaration.arity.arityInterval() ?: return emptyList()
            val nameId = Delegation.nameIdentifier(delegation) ?: return emptyList()
            val declarationFile = delegation.containingFile.originalFile

            return arityInterval.closed().map { arity ->
                FunctionSymbol(
                    declarationFile,
                    UseSymbols.nameRange(nameId),
                    owner.moduleName,
                    declaration.name,
                    arity,
                    isMacro(declaration.capabilities),
                    owner.injected
                )
            }
        }
    }
}
