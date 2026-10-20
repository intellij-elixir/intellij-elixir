package org.elixir_lang.model.psi

import com.intellij.model.Pointer
import com.intellij.openapi.application.ReadAction
import com.intellij.refactoring.rename.api.RenameTarget
import com.intellij.refactoring.rename.api.RenameValidationResult
import com.intellij.refactoring.rename.api.RenameValidator
import com.intellij.util.concurrency.annotations.RequiresReadLock

/**
 * An [ElixirSymbolWithUsages] the user can rename, unless it, or a symbol its rename also rewrites, is declared in
 * compiled code, in a library or SDK, or by a macro whose declarations a rename cannot rewrite, or it, or a delegation
 * its rename also rewrites, needs an `as:` its options have no place for.
 *
 * The platform offers any [RenameTarget] a symbol resolves to, and a compiled declaration has no document to edit
 * and a library's source is not the project's to edit, so only the validator can refuse.
 */
@Suppress("UnstableApiUsage")
interface ElixirRenameTarget : ElixirSymbolWithUsages, RenameTarget {
    override fun createPointer(): Pointer<out ElixirRenameTarget>

    /** Why [newName] is not a name this symbol can take, or `null` when it is. */
    fun newNameRefusal(newName: String): String? = null

    /** The symbols Find Usages lists as one function with this one, which a rename may rewrite with it. */
    @RequiresReadLock
    fun renamedWith(): List<ElixirSymbolWithUsages> = emptyList()

    /** What a rename of this symbol rewrites besides it, which the project must own as well. */
    @RequiresReadLock
    fun renameClosure(): RenameClosure = RenameClosure(renamedWith(), false, emptyList())

    /** Why this delegation cannot gain `as:`, or `null`. */
    @RequiresReadLock
    fun delegationRefusal(): String? = null

    /** The macro whose call declares this symbol when a rename cannot rewrite what that call declares, or `null`. */
    @get:RequiresReadLock
    val declaringMacro: String?
        get() = null

    override fun validator(): RenameValidator {
        val refusal = ReadAction.computeBlocking<String?, Throwable> {
            val closure = renameClosure()

            declarationRefusal(this)?.let { "$targetName cannot be renamed: it is $it" }
                ?: closure.members.firstNotNullOfOrNull { declarationRefusal(it) }
                    ?.let {
                        "$targetName cannot be renamed: a function renamed with it is $it"
                    }
                ?: delegationRefusal()?.takeIf { closure.gainsAs }?.let { "$targetName cannot be renamed: $it" }
                ?: closure.gainingAs.firstNotNullOfOrNull { it.delegationRefusal() }
                    ?.let { "$targetName cannot be renamed: a function renamed with it delegates without `as:`, and $it" }
        }

        return object : RenameValidator {
            override fun validate(newName: String): RenameValidationResult =
                (refusal ?: newNameRefusal(newName))?.let(RenameValidationResult::invalid)
                    ?: RenameValidationResult.ok()
        }
    }
}

/**
 * What a rename rewrites besides the symbol it starts from: [members], whose declarations and uses it renames, and the
 * delegations without `as:` that keep calling their targets by gaining `as:` with the old name, their targets left out
 * of [members]: the symbol's own declaration when [gainsAs], and each other one in [gainingAs].
 */
data class RenameClosure(
    val members: List<ElixirSymbolWithUsages>,
    val gainsAs: Boolean,
    val gainingAs: List<ElixirRenameTarget>
)

/** Where [symbol] is declared when the project cannot edit it, or `null` when it can. */
@RequiresReadLock
internal fun declarationRefusal(symbol: ElixirSymbolWithUsages): String? =
    when {
        symbol.compiledFile != null -> "declared in compiled code"
        symbol.declaredInLibrary -> "declared outside the project, in a library or SDK"
        else -> (symbol as? ElixirRenameTarget)?.declaringMacro?.let { "declared by `$it`" }
    }
