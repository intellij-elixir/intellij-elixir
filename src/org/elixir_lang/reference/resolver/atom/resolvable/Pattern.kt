package org.elixir_lang.reference.resolver.atom.resolvable

import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.ResolveResult
import com.intellij.psi.stubs.StubIndex
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.Module
import org.elixir_lang.psi.stub.index.ModularName
import org.elixir_lang.reference.resolver.atom.Resolvable
import java.util.function.Predicate
import java.util.regex.Pattern
import org.elixir_lang.reference.resolver.Module as ModuleResolver

class Pattern(private val predicate: Predicate<String>) : Resolvable() {
    constructor(regex: String) : this(Pattern.compile(regex))
    constructor(pattern: Pattern) : this(pattern.asMatchPredicate())

    override fun resolve(element: ElixirAtom): Array<ResolveResult> {
        val names = mutableListOf<String>()
        StubIndex.getInstance().processAllKeys(ModularName.KEY, element.project) { name ->
            if (Module.atom(name)?.let(predicate::test) == true) names.add(name)

            true
        }

        return names
            .flatMap { name -> ModuleResolver.resolvePreferred(element, name, incompleteCode = false, inScope = false) }
            .map { PsiElementResolveResult(it.element, false) }
            .toTypedArray()
    }
}
