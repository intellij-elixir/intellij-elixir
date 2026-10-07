package org.elixir_lang.model.psi

import com.intellij.model.Pointer
import com.intellij.openapi.application.ReadAction
import com.intellij.refactoring.rename.api.RenameTarget
import com.intellij.refactoring.rename.api.RenameValidationResult
import com.intellij.refactoring.rename.api.RenameValidator

/**
 * An [ElixirSymbolWithUsages] the user can rename, unless it is declared in compiled code or in a library or SDK.
 *
 * The platform offers any [RenameTarget] a symbol resolves to, and a compiled declaration has no document to edit
 * and a library's source is not the project's to edit, so only the validator can refuse.
 */
@Suppress("UnstableApiUsage")
interface ElixirRenameTarget : ElixirSymbolWithUsages, RenameTarget {
    override fun createPointer(): Pointer<out ElixirRenameTarget>

    /** Why [newName] is not a name this symbol can take, or `null` when it is. */
    fun newNameRefusal(newName: String): String? = null

    override fun validator(): RenameValidator {
        val refusal = ReadAction.computeBlocking<String?, Throwable> {
            when {
                compiledFile != null -> "$targetName cannot be renamed: it is declared in compiled code"
                declaredInLibrary ->
                    "$targetName cannot be renamed: it is declared outside the project, in a library or SDK"
                else -> null
            }
        }

        return object : RenameValidator {
            override fun validate(newName: String): RenameValidationResult =
                (refusal ?: newNameRefusal(newName))?.let(RenameValidationResult::invalid)
                    ?: RenameValidationResult.ok()
        }
    }
}
