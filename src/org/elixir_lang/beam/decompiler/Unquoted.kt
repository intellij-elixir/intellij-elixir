package org.elixir_lang.beam.decompiler

import org.elixir_lang.NameArity
import org.elixir_lang.code.InspectAtom
import java.lang.StringBuilder

/** A definition whose name does not parse bare in its head, written `unquote(:name)`. */
object Unquoted : Default() {
    override fun accept(beamLanguage: String, nameArity: NameArity): Boolean =
        InspectAtom.localCall(nameArity.name) != nameArity.name

    override fun appendName(decompiled: StringBuilder, name: String) {
        decompiled.append(InspectAtom.localCall(name))
    }
}
