package org.elixir_lang.model.psi.variable

import com.intellij.find.usages.api.SearchTarget
import com.intellij.find.usages.api.UsageHandler
import com.intellij.icons.AllIcons
import com.intellij.model.Pointer
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.platform.backend.navigation.NavigationRequest
import com.intellij.platform.backend.navigation.NavigationTarget
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.search.SearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.model.psi.ElixirSymbolWithUsages
import org.elixir_lang.psi.ElixirVariable
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.operation.Match
import org.elixir_lang.psi.UnaryOperation
import org.elixir_lang.psi.UnqualifiedBracketOperation
import org.elixir_lang.psi.UnqualifiedNoArgumentsCall
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.declarations.UseScopeImpl
import org.elixir_lang.psi.call.name.Function
import org.elixir_lang.psi.call.name.Module
import org.elixir_lang.psi.scope.variable.BindingPattern
import org.elixir_lang.psi.scope.variable.MultiResolve
import java.util.*

@Suppress("UnstableApiUsage")
class VariableSymbol(
    override val file: PsiFile,
    override val range: TextRange,
    val name: String,
    val kind: Kind
) : ElixirSymbolWithUsages, NavigationTarget, SearchTarget {
    enum class Kind {
        PARAMETER,
        VARIABLE,
        IGNORED
    }

    override val searchText: String get() = name
    override val targetName: String get() = name

    override fun createPointer(): Pointer<out VariableSymbol> {
        val name = this.name
        val kind = this.kind
        // Anchor to the enclosing call-definition clause (the `def`/`defp`/... a large, structurally
        // stable ancestor) and recompute the occurrence by its offset within that clause. A variable's
        // own name element is essentially the identifier leaf, and a plain file-range marker collapses:
        // an in-place (Shift+F6) rename replaces the whole identifier, which invalidates a pointer
        // anchored to that leaf/marker. The programmatic commit then reverts the live edit and
        // re-dereferences the target pointer; if it is null the whole rename is dropped (a no-op). The
        // enclosing clause survives the identifier replacement, so the occurrence range is recomputed
        // from the (restored) clause on dereference.
        val clause = generateSequence(file.findElementAt(range.startOffset)) { it.parent }
            .filterIsInstance<Call>()
            .firstOrNull { CallDefinitionClause.`is`(it) }
        if (clause != null && clause.textRange.contains(range)) {
            val clausePointer = SmartPointerManager.getInstance(file.project)
                .createSmartPsiElementPointer(clause, file)
            val relativeStart = range.startOffset - clause.textRange.startOffset
            val length = range.length
            return Pointer {
                val restoredClause = clausePointer.dereference() ?: return@Pointer null
                val start = restoredClause.textRange.startOffset + relativeStart
                if (start + length > restoredClause.textRange.endOffset) return@Pointer null
                VariableSymbol(restoredClause.containingFile, TextRange(start, start + length), name, kind)
            }
        }
        return Pointer.fileRangePointer(file, range) { restoredFile, restoredRange ->
            VariableSymbol(restoredFile, restoredRange, name, kind)
        }
    }

    override fun computePresentation(): TargetPresentation = presentation()

    override fun navigationRequest(): NavigationRequest? =
        NavigationRequest.sourceNavigationRequest(file, range)

    /**
     * The scope every usage lies in, or `null` when there is no such local scope: a declaration inside `quote` is
     * read wherever the quote is expanded, so its usages are found by the word search alone.
     */
    override val maximalSearchScope: SearchScope?
        @RequiresReadLock get() = chainRootDeclaration()?.takeUnless { isQuoted(it) }?.let { UseScopeImpl.get(it) }

    /** The declaration this symbol stands on, or `null` when it stands on a read. */
    @RequiresReadLock
    fun declaration(): UnqualifiedNoArgumentsCall<*>? =
        (declarationCall() as? UnqualifiedNoArgumentsCall<*>)?.takeIf { isDeclaration(it) }

    /** The chain root of the declaration this symbol stands on, or `null` when it stands on none. */
    @RequiresReadLock
    fun chainRootDeclaration(): UnqualifiedNoArgumentsCall<*>? = declaration()?.let { chainRootOf(it) }

    /**
     * This variable's identity for search and rename: its chain root, as a symbol.
     *
     * Two occurrences are the same variable exactly when their chain roots are equal, which is
     * what lets a usage search tell an inner binding from the outer one it shadows.
     */
    @RequiresReadLock
    fun chainRootSymbol(): VariableSymbol? =
        (declarationCall() as? UnqualifiedNoArgumentsCall<*>)
            ?.let { chainRootOf(it) }
            ?.let { fromElement(it) }

    override val usageHandler: UsageHandler
        get() = UsageHandler.createEmptyUsageHandler(name)

    override fun presentation(): TargetPresentation =
        TargetPresentation.builder(name)
            .icon(AllIcons.Nodes.Variable)
            .containerText(kind.name.lowercase())
            .presentation()

    override fun equals(other: Any?): Boolean =
        other is VariableSymbol &&
            other.name == name &&
            other.kind == kind &&
            other.file.virtualFile == file.virtualFile &&
            other.range == range

    override fun hashCode(): Int = Objects.hash(name, kind, file.virtualFile, range)

    override fun toString(): String = "VariableSymbol($name, $kind, $range)"

    @RequiresReadLock
    private fun declarationCall(): Call? =
        generateSequence(file.findElementAt(range.startOffset)) { it.parent }
            .filterIsInstance<Call>()
            .firstOrNull { call ->
                call.functionNameElement()?.textRange == range
            }

    companion object {
        /**
         * The earliest same-named declaration in this declaration's rebinding chain - possibly itself.
         *
         * A rebinding (`x = x + 1` after `x = input`) SHADOWS the earlier binding, but the chain
         * reads and writes one user-facing variable, so search and rename must span the whole chain.
         * A use scope anchored at a later rebinding starts at its own statement and would miss the
         * earlier bindings and the reads that resolve to them. What an assignment rebinds is what a
         * read just before it resolves to, so the chain is followed with the resolver, which already
         * knows that a binding inside a preceding `if` branch does not leak out and that a top-level
         * statement binds for the statements after it. The chain ends at a binding that starts afresh:
         * a parameter, a clause pattern, or the left of `<-`. Companion functions, so the cached value's
         * provider depends on the declaration alone and retains no symbol.
         */
        @RequiresReadLock
        private fun chainRootOf(declaration: UnqualifiedNoArgumentsCall<*>): UnqualifiedNoArgumentsCall<*> =
            CachedValuesManager.getCachedValue(declaration) {
                val name = variableName(declaration)
                CachedValueProvider.Result.create(
                    if (name == null) declaration else followChain(declaration, name),
                    PsiModificationTracker.MODIFICATION_COUNT
                )
            }

        @RequiresReadLock
        private fun followChain(root: UnqualifiedNoArgumentsCall<*>, name: String): UnqualifiedNoArgumentsCall<*> {
            ProgressManager.checkCanceled()
            // a pattern binds a name once however often it writes it, so its first writing is the root
            freshPattern(root)?.let { pattern -> return firstWritingIn(pattern, name) ?: root }

            val earlier = MultiResolve.earlierBindings(name, root)
                .filterIsInstance<UnqualifiedNoArgumentsCall<*>>()
                .filter {
                    it.containingFile == root.containingFile &&
                        it.textRange.startOffset < root.textRange.startOffset &&
                        isDeclaration(it)
                }
                .minByOrNull { it.textRange.startOffset } ?: return root

            // each link's own root is cached, so a chain of k links costs k lookups over the whole chain
            return chainRootOf(earlier)
        }

        @RequiresReadLock
        private fun firstWritingIn(pattern: PsiElement, name: String): UnqualifiedNoArgumentsCall<*>? =
            PsiTreeUtil.findChildrenOfType(pattern, UnqualifiedNoArgumentsCall::class.java)
                .filter { variableName(it) == name && isDeclaration(it) }
                .minByOrNull { it.textRange.startOffset }

        /**
         * The pattern that binds [declaration] anew and so starts its chain. `null` for an assignment, which
         * rebinds what the name already meant.
         */
        private fun freshPattern(declaration: PsiElement): PsiElement? = BindingPattern.of(declaration)

        /** Whether [element] sits inside a `quote`, whose bindings are read wherever the quote is expanded. */
        private fun isQuoted(element: PsiElement): Boolean =
            generateSequence(element.parent) { it.parent }
                .filterIsInstance<Call>()
                .any { it.isCalling(Module.KERNEL, Function.QUOTE) }

        @RequiresReadLock
        fun fromDeclaration(call: Call): VariableSymbol? {
            if (!isDeclaration(call)) return null
            return fromElement(call)
        }

        @RequiresReadLock
        fun fromDeclaration(element: PsiElement): VariableSymbol? {
            if (!isDeclaration(element)) return null
            return fromElement(element)
        }

        @RequiresReadLock
        fun fromCall(call: Call): VariableSymbol? {
            return fromElement(call)
        }

        @RequiresReadLock
        fun fromElement(element: PsiElement): VariableSymbol? {
            val kind = classify(element) ?: return null
            val nameElement = nameIdentifierElement(element) ?: return null
            val name = variableName(element) ?: return null

            return VariableSymbol(element.containingFile, nameElement.textRange, name, kind)
        }

        @RequiresReadLock
        fun classify(element: PsiElement): Kind? {
            val occurrence = element is UnqualifiedNoArgumentsCall<*> || element is ElixirVariable ||
                element is UnqualifiedBracketOperation
            if (!occurrence) return null
            val name = variableName(element) ?: return null

            return when {
                // the receiver of `m[k]` reads `m`
                element is UnqualifiedBracketOperation ->
                    if (name == org.elixir_lang.reference.Callable.IGNORED) Kind.IGNORED else Kind.VARIABLE
                org.elixir_lang.reference.Callable.isIgnored(element) ->
                    Kind.IGNORED
                org.elixir_lang.reference.Callable.isParameter(element) || org.elixir_lang.reference.Callable.isParameterWithDefault(element) ->
                    Kind.PARAMETER
                org.elixir_lang.reference.Callable.isVariable(element) ->
                    Kind.VARIABLE
                element is ElixirVariable ->
                    if ((variableName(element) ?: "").startsWith("_")) Kind.IGNORED else Kind.VARIABLE
                else -> null
            }
        }

        @RequiresReadLock
        fun isDeclaration(call: Call): Boolean = isDeclaration(call as PsiElement)

        /**
         * A pattern binds what it writes; elsewhere only the left of a match binds, and then only when the
         * nearest match does not read the name on its right.
         */
        @RequiresReadLock
        fun isDeclaration(element: PsiElement): Boolean {
            if (classify(element) == null || CallDefinitionClause.isHead(element)) return false
            if (BindingPattern.of(element) != null) return true
            if (BindingPattern.isGuardRead(element) || BindingPattern.isMatchRead(element)) return false

            return isVariableDeclaration(element)
        }

        /** A read of the match is already ruled out, so a match enclosing [element] within its definition binds it. */
        @RequiresReadLock
        private fun isVariableDeclaration(element: PsiElement): Boolean =
            !isPinnedSite(element) &&
                generateSequence(element) { it.parent }
                    .takeWhile { it !is PsiFile && !(it is Call && CallDefinitionClause.`is`(it)) }
                    .any { it is Match }

        @RequiresReadLock
        private fun isPinnedSite(element: PsiElement): Boolean =
            generateSequence(element) { it.parent }
                .filterIsInstance<UnaryOperation>()
                .any { unary ->
                    unary.operator().text == "^" &&
                        unary.operand()?.let { operand -> PsiTreeUtil.isAncestor(operand, element, false) } == true
                }

        @RequiresReadLock
        fun isHead(element: PsiElement): Boolean {
            val call = when (element) {
                is Call -> element
                else -> generateSequence(element) { it.parent }
                    .filterIsInstance<Call>()
                    .firstOrNull()
            } ?: return false
            return fromDeclaration(call) != null
        }

        @RequiresReadLock
        fun variableName(element: PsiElement): String? =
            when (element) {
                is Call -> element.functionName()
                is ElixirVariable -> element.name
                is UnqualifiedBracketOperation -> element.identifier.text
                else -> null
            }

        @RequiresReadLock
        fun nameIdentifierElement(element: PsiElement): PsiElement? =
            when (element) {
                is Call -> element.functionNameElement()
                is PsiNameIdentifierOwner -> element.nameIdentifier ?: element
                is UnqualifiedBracketOperation -> element.identifier
                else -> null
            }
    }
}
