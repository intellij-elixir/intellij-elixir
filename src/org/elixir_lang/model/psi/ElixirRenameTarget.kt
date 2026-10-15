package org.elixir_lang.model.psi

import com.intellij.model.Pointer
import com.intellij.openapi.application.ReadAction
import com.intellij.refactoring.rename.api.RenameTarget
import com.intellij.refactoring.rename.api.RenameValidationResult
import com.intellij.refactoring.rename.api.RenameValidator
import com.intellij.util.concurrency.annotations.RequiresReadLock

/**
 * An [ElixirSymbolWithUsages] the user can rename, unless it, or a symbol its rename also rewrites, is declared in
 * compiled code, in a library or SDK, or by a macro whose declarations a rename cannot rewrite.
 *
 * The platform offers any [RenameTarget] a symbol resolves to, and a compiled declaration has no document to edit
 * and a library's source is not the project's to edit, so only the validator can refuse.
 */
@Suppress("UnstableApiUsage")
interface ElixirRenameTarget : ElixirSymbolWithUsages, RenameTarget {
    override fun createPointer(): Pointer<out ElixirRenameTarget>

    /** Why [newName] is not a name this symbol can take, or `null` when it is. */
    fun newNameRefusal(newName: String): String? = null

    /** The symbols whose declarations a rename of this one also rewrites, which the project must own as well. */
    @RequiresReadLock
    fun renamedWith(): List<ElixirSymbolWithUsages> = emptyList()

    /** The macro whose call declares this symbol when a rename cannot rewrite what that call declares, or `null`. */
    @get:RequiresReadLock
    val declaringMacro: String?
        get() = null

    override fun validator(): RenameValidator {
        val refusal = ReadAction.computeBlocking<String?, Throwable> {
            declarationRefusal(this)?.let { "$targetName cannot be renamed: it is $it" }
                ?: renamedWith().firstNotNullOfOrNull { declarationRefusal(it) }
                    ?.let {
                        "$targetName cannot be renamed: a function renamed with it is $it"
                    }
        }

        return object : RenameValidator {
            override fun validate(newName: String): RenameValidationResult =
                (refusal ?: newNameRefusal(newName))?.let(RenameValidationResult::invalid)
                    ?: RenameValidationResult.ok()
        }
    }
}

/** Where [symbol] is declared when the project cannot edit it, or `null` when it can. */
@RequiresReadLock
private fun declarationRefusal(symbol: ElixirSymbolWithUsages): String? =
    when {
        symbol.compiledFile != null -> "declared in compiled code"
        symbol.declaredInLibrary -> "declared outside the project, in a library or SDK"
        else -> (symbol as? ElixirRenameTarget)?.declaringMacro?.let { "declared by `$it`" }
    }
