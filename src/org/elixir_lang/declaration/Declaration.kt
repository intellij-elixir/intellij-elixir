package org.elixir_lang.declaration

import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import org.elixir_lang.call.Visibility
import org.elixir_lang.psi.arityInterval

/** A callable declaration, source or compiled. */
data class Declaration(
    val name: String,
    val arity: ArityKnowledge,
    val capabilities: Capabilities,
    val declared: Declared
) {
    val visibility: Visibility get() = capabilities.visibility

    /**
     * Whether this declaration and [name]`/`[arity] can be one function: the same name at an arity both take.
     * Another name is another function whatever the arities; with the same name, an [ArityKnowledge.Unknown] arity
     * is [SameFunction.UNKNOWN], which each caller decides for itself.
     */
    fun sameFunction(name: String, arity: ArityKnowledge): SameFunction {
        if (this.name != name) return SameFunction.NO

        val interval = this.arity.arityInterval() ?: return SameFunction.UNKNOWN
        val otherInterval = arity.arityInterval() ?: return SameFunction.UNKNOWN

        return if (interval.overlaps(otherInterval)) SameFunction.YES else SameFunction.NO
    }

    fun sameFunction(other: Declaration): SameFunction = sameFunction(other.name, other.arity)
}

enum class SameFunction { YES, NO, UNKNOWN }

/** The syntactic form a source declaration takes. */
enum class Form { CLAUSE, CALLBACK, DELEGATION, EXCEPTION, EEX_FUNCTION_FROM, GENERATOR_EMBED }

/** Where a declaration came from. */
sealed class Declared {
    data class Source(val form: Form, val origin: SourceOrigin) : Declared()

    data class Compiled(val origin: CompiledOrigin) : Declared()
}

/**
 * The declaring call's [range] in [file] as it was at [modificationStamp]. The stamp is the view provider's: a
 * `PsiFile`'s restarts at 0 when its PSI is rebuilt from a changed file. [file] may be a compiled file, with
 * [range] in its current decompiled text.
 */
data class SourceOrigin(val file: VirtualFile, val modificationStamp: Long, val range: TextRange)

/**
 * An export of [module], named as `defmodule` names it (`Kernel`, `:lists`), in the `.beam` [file]. Whether it is a
 * `MACRO-` export is the declaration's `compileTime`.
 */
data class CompiledOrigin(val file: VirtualFile, val module: String, val name: String, val arity: Int)
