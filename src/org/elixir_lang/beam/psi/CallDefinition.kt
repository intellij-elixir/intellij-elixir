package org.elixir_lang.beam.psi

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiCompiledFile
import com.intellij.psi.PsiManager
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.NameArityInterval
import org.elixir_lang.declaration.Capabilities
import org.elixir_lang.declaration.CompiledOrigin
import org.elixir_lang.declaration.Declaration
import org.elixir_lang.declaration.Declared
import org.elixir_lang.declaration.Definer
import org.elixir_lang.psi.arityKnowledge
import org.elixir_lang.psi.call.MaybeExported
import org.elixir_lang.structure_view.element.Timed.Time

interface CallDefinition : BeamSymbol, MaybeExported {
    /** The decompiled module this definition belongs to. */
    override fun getParent(): Module

    val time: Time
    val nameArityInterval: NameArityInterval

    /** The first clause's parameters as the stub stores them. */
    val parameters: List<String>

    /** The head a function with default arguments is written with, the same at each arity they cover; empty if none. */
    val head: List<String>

    /** A `defguard` is a `MACRO-` export, which the stub cannot tell from a `defmacro`. */
    @get:RequiresReadLock
    val capabilities: Capabilities
        get() = when (time) {
            Time.COMPILE -> if (isExported) Definer.DEFMACRO else Definer.DEFMACROP
            Time.RUN -> if (isExported) Definer.DEF else Definer.DEFP
        }.capabilities

    @RequiresReadLock
    fun declaration(): Declaration =
        Declaration(
            nameArityInterval.name,
            nameArityInterval.arityInterval.arityKnowledge(),
            capabilities,
            Declared.Compiled(
                CompiledOrigin(
                    containingFile.virtualFile,
                    parent.name,
                    nameArityInterval.name,
                    nameArityInterval.arityInterval.minimum
                )
            )
        )
}

/** `module_info/0,1` or `__info__/1`, which the compiler adds to a module and no `import` of it brings in. */
val CallDefinition.isCompilerAdded: Boolean
    get() = nameArityInterval.let { (name, arityInterval) ->
        (name == "module_info" && arityInterval.minimum in 0..1) || (name == "__info__" && arityInterval.minimum == 1)
    }

/** The definition of this origin's name and arity; a module cannot define both a function and a macro of one. */
@RequiresReadLock
fun CompiledOrigin.callDefinition(project: Project): CallDefinition? =
    (file.takeIf { it.isValid }?.let { PsiManager.getInstance(project).findFile(it) } as? PsiCompiledFile)
        ?.children
        ?.filterIsInstance<Module>()
        ?.singleOrNull { it.name == module }
        ?.callDefinitions()
        ?.singleOrNull { it.nameArityInterval.name == name && it.nameArityInterval.arityInterval.minimum == arity }
