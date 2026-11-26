package org.elixir_lang.injection

import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.text.TextRangeUtil
import org.elixir_lang.psi.Bodied
import org.elixir_lang.psi.ElixirInterpolation
import org.elixir_lang.psi.HeredocLiteral

/**
 * The parts of [range], relative to [host], that lie outside [host]'s interpolations: the code in `#{...}` is Elixir, so
 * a language injected into the text around it must not read it.
 *
 * Only top-level interpolations are cut: a nested one lies inside its outer one. A [range] of length 0 has no part.
 */
internal fun partsOutsideInterpolations(host: PsiElement, range: TextRange): List<TextRange> =
    if (range.isEmpty) emptyList() else TextRangeUtil.excludeRanges(range, interpolationRanges(host)).toList()

private val INTERPOLATION_RANGES: Key<CachedValue<List<TextRange>>> = Key.create("ELIXIR_INJECTION_INTERPOLATION_RANGES")

/**
 * [host]'s top-level interpolations in order, relative to [host]. Cached per host: the documentation injector asks once
 * per code line.
 */
private fun interpolationRanges(host: PsiElement): List<TextRange> =
    CachedValuesManager.getCachedValue(host, INTERPOLATION_RANGES) {
        CachedValueProvider.Result.create(computeInterpolationRanges(host), host)
    }

private fun computeInterpolationRanges(host: PsiElement): List<TextRange> {
    val hostStart = host.textRange.startOffset
    val bodies = when (host) {
        is HeredocLiteral -> host.heredocLineList.map { it.body }
        is Bodied -> listOf(host.body)
        else -> emptyList()
    }

    return bodies.flatMap { body ->
        PsiTreeUtil.getChildrenOfType(body, ElixirInterpolation::class.java).orEmpty().map { it.textRange.shiftLeft(hostStart) }
    }
}
