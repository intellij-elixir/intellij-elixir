package org.elixir_lang.psi

import com.intellij.psi.PsiElement
import org.elixir_lang.Arity
import org.elixir_lang.ArityRange
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.name.Module
import org.elixir_lang.psi.impl.ElixirPsiImplUtil
import org.elixir_lang.psi.operation.InMatch
import org.elixir_lang.psi.operation.Operation

/**
 * While an arity range for a normal function or macro is represented as an
 * [org.apache.commons.lang.math.IntRange], some special forms have no fixed arity when used because although
 * defined in `Kernel.SpecialForms` as 1-arity, that one argument is effectively a splat of all the arguments, so
 * there needs to be a way to represent that half-open interval.
 *
 * Open intervals are also where `unquote_splicing(args)` is used as the length of `args` is unknown.
 */
data class ArityInterval(val minimum: Arity, val maximum: Arity?) {
    /**
     * Unlike [org.apache.commons.lang.math.IntRange.IntRange]}, where the argument becomes the minimum AND
     * the maximum, here the argument is ONLY the minimum and the interval has an infinite maximum.
     *
     * @param minimum minimum arity
     */
    constructor(minimum: Arity): this(minimum, null)

    fun addArguments(arguments: Array<PsiElement>?): ArityInterval =
        if (arguments != null) {
            arguments.fold(this) { acc, argument ->
                acc.addArgument(argument)
            }
        } else {
            this
        }

    fun addArgument(argument: PsiElement): ArityInterval =
            when {
                argument.isDefaultArgument() -> addDefaultArgument()
                argument.isUnquoteSplicingArgument() -> addUnquoteSplicingArgument()
                else -> addArgument()
            }

    fun addArgument(): ArityInterval =
            if (maximum != null) {
                copy(minimum = minimum + 1, maximum = maximum + 1)
            } else {
                copy(minimum = minimum + 1)
            }

    fun addDefaultArgument(): ArityInterval =
            if (maximum != null) {
                copy(maximum = maximum + 1)
            } else {
                this
            }

    fun addUnquoteSplicingArgument(): ArityInterval = copy(maximum = null)

    fun closed(): ArityRange =
            if (maximum != null) {
                (minimum..maximum)
            } else {
                (minimum..minimum)
            }

    /**
     * The arity naming the function a declaration of this interval belongs to: its highest, which a bodiless head with
     * defaults and the clauses after it share, and Elixir rejects two declarations whose defaults overlap. `null` for an
     * open interval, which has no highest.
     */
    val functionArity: Arity? get() = maximum

    operator fun contains(candidate: Arity): Boolean {
        return minimum <= candidate && (maximum == null || candidate <= maximum)
    }

    fun overlaps(other: ArityInterval): Boolean =
            // https://stackoverflow.com/questions/325933/determine-whether-two-date-ranges-overlap/325964#325964
            (other.maximum == null || this.minimum <= other.maximum) &&
                    (this.maximum == null || this.maximum >= other.minimum)

    fun overlaps(arityRange: ArityRange): Boolean =
        this.minimum < arityRange.start && (this.maximum == null || this.maximum >= arityRange.endInclusive)

    fun singleOrNull(): Arity? =
            if (minimum == maximum) {
                minimum
            } else {
                null
            }

    override fun toString(): String {
        val stringBuilder = StringBuilder("ArityInterval(").append(minimum)

        if (maximum != null) {
            stringBuilder.append(", ").append(maximum)
        }

        stringBuilder.append(")")

        return stringBuilder.toString()
    }

    companion object {
        private val EMPTY = ArityInterval(0, 0)

        fun empty(): ArityInterval = EMPTY

        fun fromArguments(arguments: Array<PsiElement>?): ArityInterval =
            empty().addArguments(arguments)
    }
}

fun ArityInterval?.orEmpty(): ArityInterval = this ?: ArityInterval.empty()

/**
 * Whether the given element presents a default argument (with `\\` in it.
 *
 * @param this@isDefaultArgument an argument to a [Call]
 * @return `true` if in match operation with `\\` operator; otherwise, `false`.
 */
private fun PsiElement.isDefaultArgument(): Boolean {
    var defaultArgument = false

    if (this is InMatch) {
        val operation = this as Operation

        if (operation.operator().text.trim { it <= ' ' } == ElixirPsiImplUtil.DEFAULT_OPERATOR) {
            defaultArgument = true
        }
    }

    return defaultArgument
}

private fun PsiElement.isUnquoteSplicingArgument(): Boolean =
        this is Call && isCalling(Module.KERNEL, "unquote_splicing", 1)
