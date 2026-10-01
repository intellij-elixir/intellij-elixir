package org.elixir_lang.lowering

import com.ericsson.otp.erlang.OtpErlangAtom
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * [ElementLowering.quote] against `Quotable.quote()` on each of [quotedElements], and [ElementLowering.atomName]
 * against the atom read from `quote()` on each whose atom production code reads. An element both refuse agrees.
 */
class ElementDifferential {
    private val counts = sortedMapOf<String, Int>()
    private val disagreements = mutableListOf<String>()

    fun compare(path: String, file: PsiFile) {
        for ((kind, element) in quotedElements(file)) {
            record(path, file, element, "quote\t$kind", runCatching { element.quote() }, runCatching { ElementLowering.quote(element) })

            if (kind in ATOM_KINDS) {
                // An atom over 255 code points throws from `OtpErlangAtom`'s constructor, which a read took as no name.
                val baseName = runCatching {
                    try {
                        element.quote() as? OtpErlangAtom
                    } catch (_: IllegalArgumentException) {
                        null
                    }?.atomValue()
                }
                record(path, file, element, "atomName\t$kind", baseName, runCatching { ElementLowering.atomName(element) })
            }
        }
    }

    fun counts(): Map<String, Int> = counts

    fun disagreements(): List<String> = disagreements

    private fun record(path: String, file: PsiFile, element: PsiElement, key: String, base: Result<Any?>, entry: Result<Any?>) {
        val agrees =
            if (base.isFailure || entry.isFailure) {
                base.exceptionOrNull()?.javaClass == entry.exceptionOrNull()?.javaClass
            } else {
                base.getOrNull() == entry.getOrNull()
            }
        counts.merge("$key\t${if (agrees) "agrees" else "differs"}", 1, Int::plus)

        if (!agrees && disagreements.size < 20) {
            val line = StringUtil.offsetToLineNumber(file.text, element.textRange.startOffset) + 1
            disagreements += "$key\t$path:$line\t`${element.text.take(80).replace("\n", "\\n")}`\n" +
                "    base:  ${base.fold({ "$it" }, { "throws $it" }).take(300)}\n" +
                "    entry: ${entry.fold({ "$it" }, { "throws $it" }).take(300)}"
        }
    }
}
