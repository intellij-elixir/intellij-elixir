package org.elixir_lang.beam.psi

import org.elixir_lang.NameArityInterval
import org.elixir_lang.psi.call.MaybeExported
import org.elixir_lang.structure_view.element.Timed.Time

interface CallDefinition : BeamSymbol, MaybeExported {
    /** The decompiled module this definition belongs to. */
    override fun getParent(): Module

    val time: Time
    val nameArityInterval: NameArityInterval

    /** The first clause's parameters as the stub stores them. */
    val parameters: List<String>

    /** Whether its docs mark it `guard: true`. */
    val isGuard: Boolean
}
