package org.elixir_lang.model.psi.config

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.expander.ImportMatch
import org.elixir_lang.psi.Sigil
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.qualification.Qualified
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.moduleName
import org.elixir_lang.psi.impl.stripAccessExpressionAndParentheses
import org.elixir_lang.utils.ElixirModulesUtil

/**
 * How a call names its module, as written, and the [name] and [arity] it calls. Reads, writes of the env and `config`
 * calls each ask [reaches] whether the call reaches a module of theirs.
 */
data class CallModule(val qualifier: Qualifier, val name: String, val arity: Int) {
    sealed interface Qualifier {
        /** An alias or an atom, naming [module] as written; an `alias` can make an alias's first name another module. */
        data class Written(val module: String) : Qualifier

        /** Any other qualifier: a module attribute, a variable, an alias headed by `__MODULE__`. */
        data object Other : Qualifier

        data object Unqualified : Qualifier
    }

    /** Whether a call reaches one of some modules. */
    enum class Answer {
        REACHES,
        ELSEWHERE,

        /** No one module can be named: only expanding a template's own Elixir, or a value not known, could tell. */
        UNDECIDED,
    }

    /**
     * Whether [call], written as this says, calls one of [modules]' [name] and [arity]: its qualifier names one, or,
     * unqualified, an import of one brings it in, in the env the file's expansion reached [call] in. Where expansion did
     * not reach, [call] is named as written: its qualifier, or, unqualified, an `import` of one at the file's top level,
     * or a `use` of `Mix.Config`. In a template [sigil] holds, each is named where [KeyAtom.place] says.
     */
    @RequiresReadLock
    fun reaches(call: Call, modules: Collection<String>, sigil: Sigil? = KeyAtom.injectedSigil(call)): Answer =
        when (qualifier) {
            Qualifier.Unqualified -> {
                val at = KeyAtom.place(call, sigil) ?: return Answer.UNDECIDED

                when (val match = KeyAtom.importOf(call, name, arity, at)) {
                    is ImportMatch.Function -> answer(match.receiver in modules)
                    is ImportMatch.Macro -> answer(match.receiver in modules)
                    is ImportMatch.Quoted -> answer(match.receiver in modules)
                    ImportMatch.None -> Answer.ELSEWHERE
                    is ImportMatch.Ambiguous, ImportMatch.Unreadable -> Answer.UNDECIDED
                    null -> answer(imports(call.containingFile, modules))
                }
            }
            is Qualifier.Written, Qualifier.Other -> {
                val written = (call as Qualified).qualifier()

                when (val named = KeyAtom.receiver(written, KeyAtom.place(written, sigil) ?: return Answer.UNDECIDED)) {
                    is KeyAtom.Named.Module -> answer(named.name in modules)
                    KeyAtom.Named.Unknown -> Answer.UNDECIDED
                    null -> (qualifier as? Qualifier.Written)?.let { answer(it.module in modules) } ?: Answer.UNDECIDED
                }
            }
        }

    /** The call this names nearest [element]: [element] itself, or the call it is an argument of, outward. */
    @RequiresReadLock
    fun callAt(element: PsiElement): Call? =
        generateSequence((element as? Call) ?: Keys.call(element)) { Keys.call(it) }.firstOrNull { of(it) == this }

    private fun answer(reaches: Boolean): Answer = if (reaches) Answer.REACHES else Answer.ELSEWHERE

    companion object {
        /** How [call] names its module and what it calls; `null` for a call with no name. */
        @RequiresReadLock
        fun of(call: Call): CallModule? = call.functionName()?.let { CallModule(qualifier(call), it, call.resolvedFinalArity()) }

        /** [call]'s qualifier as written. */
        @RequiresReadLock
        fun qualifier(call: Call): Qualifier {
            if (call !is Qualified) return Qualifier.Unqualified

            return written(call.qualifier())?.let(Qualifier::Written) ?: Qualifier.Other
        }

        /** The module [element], an alias or an atom, names as written. */
        @RequiresReadLock
        private fun written(element: PsiElement): String? {
            val moduleName = moduleName(element.stripAccessExpressionAndParentheses()) ?: return null
            val name = moduleName.name

            return when {
                name.startsWith(":") -> name.substring(1)
                moduleName.absolute || ElixirModulesUtil.elixirAliasSegmentsRegex.matches(name) -> "Elixir.$name"
                else -> null
            }
        }

        /** Whether [file]'s top level imports one of [modules], as written. */
        @RequiresReadLock
        private fun imports(file: PsiFile, modules: Collection<String>): Boolean =
            file.children.any { child ->
                child is Call && child !is Qualified &&
                    child.finalArguments()?.firstOrNull()?.let(::written).let { module ->
                        module in modules && (child.functionName() == "import" || child.functionName() == "use" && module == MIX_CONFIG)
                    }
            }

        private const val MIX_CONFIG = "Elixir.Mix.Config"
    }
}
