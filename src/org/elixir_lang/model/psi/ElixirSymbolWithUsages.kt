package org.elixir_lang.model.psi

import com.intellij.model.Pointer
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiCompiledFile
import com.intellij.psi.PsiFile
import com.intellij.refactoring.rename.api.RenameTarget
import com.intellij.refactoring.rename.api.RenameValidationResult
import com.intellij.refactoring.rename.api.RenameValidator

/**
 * An [ElixirSymbol] that can be searched for. [ElixirSymbolUsageSearcher] dispatches on this type.
 *
 * @property file the file containing the declaring occurrence
 * @property range absolute range (in [file]) of the declaring occurrence, used for navigation and
 *   the self-declaration usage
 * @property searchText the bare name to anchor a text search on (e.g. `handle_call`)
 *
 * Every one is a [RenameTarget], and one declared in a compiled file refuses the rename: nothing in a `.beam` can be
 * rewritten.
 */
@Suppress("UnstableApiUsage")
interface ElixirSymbolWithUsages : ElixirSymbol, RenameTarget {
    val file: PsiFile
    val range: TextRange
    val searchText: String

    override fun createPointer(): Pointer<out ElixirSymbolWithUsages>

    override fun validator(): RenameValidator = if (isCompiled(file)) CompiledRenameValidator else RenameValidator.empty()
}

/** The compiled `.beam` [file] is, or whose decompiled text it is; `null` for a source file. */
fun compiledFileOf(file: PsiFile): PsiCompiledFile? = file as? PsiCompiledFile ?: file.originalFile as? PsiCompiledFile

fun isCompiled(file: PsiFile): Boolean = compiledFileOf(file) != null

@Suppress("UnstableApiUsage")
private object CompiledRenameValidator : RenameValidator {
    override fun validate(newName: String): RenameValidationResult =
        RenameValidationResult.invalid("A compiled definition cannot be renamed")
}
