package org.elixir_lang.reference.resolver.atom.resolvable

import com.intellij.openapi.progress.ProgressManager
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.lowering.AtomName
import org.elixir_lang.lowering.ElementLowering
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.psi.ElixirAtom
import java.io.ByteArrayOutputStream

/**
 * The names an interpolated [atom] can have, from the `:erlang.binary_to_atom(<<...>>, :utf8)` it lowers to: each text
 * part's bytes as [AtomName.text] reads them, each interpolation anything, and a UTF-8 character split by an
 * interpolation left to it. `null` when a part's bytes are not UTF-8, as Elixir raises there, and when the atom lowers
 * to anything else.
 */
@RequiresReadLock
fun interpolatedAtomPattern(atom: ElixirAtom): Pattern? {
    val parts = ((ElementLowering.lower(atom) as? ElixirAst.Call)?.arguments?.firstOrNull() as? ElixirAst.Call)
        ?.arguments
        ?: return null
    val regex = StringBuilder()
    val bytes = ByteArrayOutputStream()

    var afterInterpolation = false

    fun flush(beforeInterpolation: Boolean): Boolean {
        val text = AtomName.text(bytes.toByteArray(), afterInterpolation, beforeInterpolation) ?: return false

        bytes.reset()
        if (text.isNotEmpty()) regex.append(java.util.regex.Pattern.quote(text))

        return true
    }

    for (part in parts) {
        ProgressManager.checkCanceled()

        if (part is ElixirAst.Literal.Binary) {
            bytes.write(part.bytes)
        } else {
            if (!flush(beforeInterpolation = true)) return null
            regex.append(".*")
            afterInterpolation = true
        }
    }

    return if (flush(beforeInterpolation = false)) Pattern(regex.toString()) else null
}
