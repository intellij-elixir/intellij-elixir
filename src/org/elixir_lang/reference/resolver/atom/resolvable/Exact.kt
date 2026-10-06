package org.elixir_lang.reference.resolver.atom.resolvable

import com.intellij.psi.ResolveResult
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.reference.resolver.atom.Resolvable
import org.elixir_lang.reference.resolver.Module as ModuleResolver

class Exact(private val name: String) : Resolvable() {
    override fun resolve(element: ElixirAtom): Array<ResolveResult> =
        ModuleResolver
            .resolvePreferred(element, name, incompleteCode = false, inScope = false)
            .toTypedArray<ResolveResult>()
}
