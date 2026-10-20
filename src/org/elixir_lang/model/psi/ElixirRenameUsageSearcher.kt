package org.elixir_lang.model.psi

import com.intellij.find.usages.api.PsiUsage
import com.intellij.model.Pointer
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.refactoring.rename.api.FileOperation
import com.intellij.refactoring.rename.api.ModifiableRenameUsage
import com.intellij.refactoring.rename.api.PsiModifiableRenameUsage
import com.intellij.refactoring.rename.api.RenameUsage
import com.intellij.refactoring.rename.api.RenameUsageSearchParameters
import com.intellij.refactoring.rename.api.RenameUsageSearcher
import com.intellij.refactoring.rename.api.fileRangeUpdater
import com.intellij.util.Query
import com.intellij.util.concurrency.annotations.RequiresReadLock
import com.intellij.util.text.StringOperation
import org.elixir_lang.code.InspectAtom
import org.elixir_lang.model.psi.function.FunctionSymbol
import org.elixir_lang.structure_view.element.Delegation

/**
 * Shared [RenameUsageSearcher] for Elixir [ElixirSymbolWithUsages] targets. A thin shim: it
 * unwraps [RenameUsageSearchParameters], delegates to [ElixirUsageQueries] (the same queries as
 * Find Usages), and maps each [PsiUsage] to a modifiable rename usage.
 */
@Suppress("UnstableApiUsage")
internal class ElixirRenameUsageSearcher : RenameUsageSearcher {
    // Explicit no-op overrides of Searcher's @ApiStatus.OverrideOnly hooks; see
    // ElixirSymbolUsageSearcher for the full rationale - same trick, same reason.
    override fun collectSearchRequest(parameters: RenameUsageSearchParameters): Query<out RenameUsage>? = null

    override fun collectImmediateResults(parameters: RenameUsageSearchParameters): Collection<RenameUsage> =
        emptyList()

    override fun collectSearchRequests(parameters: RenameUsageSearchParameters): Collection<Query<out RenameUsage>> {
        val target = parameters.target as? ElixirSymbolWithUsages ?: return emptyList()
        val closure = (target as? ElixirRenameTarget)?.renameClosure() ?: RenameClosure(emptyList(), false, emptyList())
        val asInsertions = (listOfNotNull(target.takeIf { closure.gainsAs }) + closure.gainingAs)
            .filterIsInstance<FunctionSymbol>()
            .mapNotNull(::asInsertion)
            .map(::ElixirDirectUsageQuery)

        return ElixirUsageQueries.searchRequests(parameters.project, target, closure.members, parameters.searchScope)
            .map { query ->
                query.mapping { usage ->
                    val psiUsage = usage as? PsiUsage
                        ?: error("ElixirUsageQueries produced a non-PsiUsage: ${usage::class.java.name}")
                    val usageTextByName = (psiUsage as? ElixirPsiUsage)?.usageTextByName
                    if (usageTextByName != null) {
                        AdjustedTextRenameUsage(psiUsage.file, psiUsage.range, psiUsage.declaration, usageTextByName)
                    } else {
                        PsiModifiableRenameUsage.defaultPsiModifiableRenameUsage(psiUsage)
                    }
                }
            } + asInsertions
    }

    /** The `as:` that keeps [delegation] calling its targets by the name a rename takes from its head. */
    @RequiresReadLock
    private fun asInsertion(delegation: FunctionSymbol): RenameUsage? {
        val offset = delegation.delegation()?.let(Delegation::asOffset) ?: return null

        return AsInsertionRenameUsage(delegation.file, TextRange(offset, offset), ", as: ${InspectAtom.literal(delegation.name)}")
    }
}

/**
 * An `as:` a rename adds to a delegation, which a Find Usages never lists. Its updater is not a range updater, so an
 * in-place rename leaves it out of the live template and applies it once the rename commits.
 */
@Suppress("UnstableApiUsage")
private class AsInsertionRenameUsage(
    override val file: PsiFile,
    override val range: TextRange,
    private val text: String
) : PsiModifiableRenameUsage {
    override val declaration: Boolean get() = false

    override val fileUpdater: ModifiableRenameUsage.FileUpdater = object : ModifiableRenameUsage.FileUpdater {
        override fun prepareFileUpdate(usage: ModifiableRenameUsage, newName: String): Collection<FileOperation> =
            (usage as AsInsertionRenameUsage).let {
                listOf(FileOperation.modifyFile(it.file, StringOperation.insert(it.range.startOffset, it.text)))
            }
    }

    override fun createPointer(): Pointer<out PsiModifiableRenameUsage> {
        val text = this.text
        return Pointer.fileRangePointer(file, range) { restoredFile, restoredRange ->
            AsInsertionRenameUsage(restoredFile, restoredRange, text)
        }
    }
}

/**
 * A rename usage whose written text is a transformation of the target's new name - e.g. a
 * multi-alias group member gets the new name relative to the group qualifier, and a bare aliased
 * reference gets only the last segment (see [ElixirPsiUsage.usageTextByName]). Built on the
 * platform's [fileRangeUpdater], which exists for exactly this "usage text differs from target
 * name" case.
 */
@Suppress("UnstableApiUsage")
private class AdjustedTextRenameUsage(
    override val file: PsiFile,
    override val range: TextRange,
    override val declaration: Boolean,
    private val usageTextByName: (String) -> String
) : PsiModifiableRenameUsage {
    override val fileUpdater: ModifiableRenameUsage.FileUpdater = fileRangeUpdater(usageTextByName)

    override fun createPointer(): Pointer<out PsiModifiableRenameUsage> {
        val declaration = this.declaration
        val usageTextByName = this.usageTextByName
        return Pointer.fileRangePointer(file, range) { restoredFile, restoredRange ->
            AdjustedTextRenameUsage(restoredFile, restoredRange, declaration, usageTextByName)
        }
    }
}
