package org.elixir_lang.model.psi.config

import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.lowering.AtomName
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.ElixirList
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.qualification.Qualified
import org.elixir_lang.psi.impl.call.CallImpl
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.moduleName
import org.elixir_lang.psi.impl.stripAccessExpressionAndParentheses
import org.elixir_lang.psi.operation.Infix

/**
 * A candidate read of [app]'s first-level [key], written at [element]: the key's argument, or the whole [call] for
 * [WrittenKey.All]. It is a read where [module] says [call] calls a module with that read.
 */
class Read(val app: ReadApp, val key: WrittenKey, val element: PsiElement, val call: Call, val module: ReadModule) {
    companion object {
        /**
         * The candidate read [call] makes, whatever module it names, or `null` when it reads no key it can name: no module
         * has a read of its name and arity, or neither its app nor its key is written as a literal.
         */
        @RequiresReadLock
        fun of(call: Call): Read? {
            val name = call.functionName() ?: return null
            val arity = call.resolvedFinalArity()
            val shape = SHAPES[Signature(name, arity)]?.shape ?: return null
            val arguments = arguments(call).takeIf { it.size == arity } ?: return null
            val app: ReadApp =
                shape.app?.let { (arguments[it] as? ElixirAtom)?.let(AtomName::of) }?.let(ConfigIndexKey::App)
                    ?: ConfigIndexKey.AnyApp
            val (key, element) = when (val index = shape.key) {
                null -> WrittenKey.All to call
                else -> {
                    val argument = arguments[index]
                    val written =
                        if (shape.path) {
                            (argument as? ElixirList)?.children?.firstOrNull()?.stripAccessExpressionAndParentheses() ?: argument
                        } else {
                            argument
                        }

                    WrittenKey.of(written) to written
                }
            }

            return Read(app, key, element, call, ReadModule(qualifier(call), name, arity)).takeUnless {
                app == ConfigIndexKey.AnyApp && (key == WrittenKey.Unknown || key == WrittenKey.All)
            }
        }

        /**
         * What [element] names as an argument of a read that may still be being written: the app, by the shape of the
         * call's arity or, where it is no read at that arity, of the next arity up at which it is one; or a key of a
         * literal app.
         */
        @RequiresReadLock
        fun slotAt(element: PsiElement): Slot? {
            val call = Keys.call(element) ?: return null
            val name = call.functionName() ?: return null
            val arity = call.resolvedFinalArity()
            val qualifier = qualifier(call)
            val (readArity, shape) = (arity..MAX_ARITY).firstNotNullOfOrNull { readArity ->
                SHAPES[Signature(name, readArity)]
                    ?.takeIf { ReadModule(qualifier, name, readArity).isRead(call) }
                    ?.let { readArity to it.shape }
            } ?: return null

            return when {
                shape.app != null && arguments(call).getOrNull(shape.app) == element -> Slot.App
                readArity == arity -> (Keys.readAt(element)?.app as? ConfigIndexKey.App)?.let { Slot.Key(it.atom) }
                else -> null
            }
        }

        /**
         * [call]'s arguments, its piped value first, each stripped of its access expression and parentheses. None for a
         * definition clause's head, which Elixir reads as a pattern, not a call.
         */
        @RequiresReadLock
        internal fun arguments(call: Call): List<PsiElement> {
            if (CallDefinitionClause.isHead(call)) return emptyList()

            val own = call.finalArguments().orEmpty().map { it.stripAccessExpressionAndParentheses() }

            return if (CallImpl.isPipedInto(call)) {
                listOfNotNull((call.parent as Infix).leftOperand()?.stripAccessExpressionAndParentheses()) + own
            } else {
                own
            }
        }

        /** The modules with a read of [name] and [arity]. */
        fun modules(name: String, arity: Int): List<String> = SHAPES[Signature(name, arity)]?.modules.orEmpty()

        /** [call]'s qualifier as written. */
        @RequiresReadLock
        private fun qualifier(call: Call): ReadModule.Qualifier {
            if (call !is Qualified) return ReadModule.Qualifier.UNQUALIFIED
            val qualifier = call.qualifier().stripAccessExpressionAndParentheses()

            return when {
                qualifier is ElixirAtom ->
                    when (AtomName.of(qualifier)) {
                        ERLANG_APPLICATION -> ReadModule.Qualifier.ERLANG_APPLICATION
                        ELIXIR_APPLICATION -> ReadModule.Qualifier.APPLICATION
                        else -> ReadModule.Qualifier.OTHER
                    }
                moduleName(qualifier)?.name == APPLICATION -> ReadModule.Qualifier.APPLICATION
                else -> ReadModule.Qualifier.OTHER
            }
        }

        private data class Signature(val name: String, val arity: Int)

        /** Which argument is the app (`null`: the caller's, so any) and which the key (`null`: every key). */
        private data class Shape(val app: Int?, val key: Int?, val path: Boolean = false)

        /** A signature's [shape], the same in every one of [modules] that has a read of it. */
        private class Reads(val shape: Shape, val modules: List<String>)

        private const val APPLICATION = "Application"
        internal const val ERLANG_APPLICATION = "application"
        internal const val ELIXIR_APPLICATION = "Elixir.Application"

        private val SHAPES: Map<Signature, Reads> =
            listOf(
                Triple(ELIXIR_APPLICATION, Signature("get_env", 2), Shape(0, 1)),
                Triple(ELIXIR_APPLICATION, Signature("get_env", 3), Shape(0, 1)),
                Triple(ELIXIR_APPLICATION, Signature("fetch_env", 2), Shape(0, 1)),
                Triple(ELIXIR_APPLICATION, Signature("fetch_env!", 2), Shape(0, 1)),
                Triple(ELIXIR_APPLICATION, Signature("compile_env", 2), Shape(0, 1, path = true)),
                Triple(ELIXIR_APPLICATION, Signature("compile_env", 3), Shape(0, 1, path = true)),
                Triple(ELIXIR_APPLICATION, Signature("compile_env", 4), Shape(1, 2, path = true)),
                Triple(ELIXIR_APPLICATION, Signature("compile_env!", 2), Shape(0, 1, path = true)),
                Triple(ELIXIR_APPLICATION, Signature("compile_env!", 3), Shape(1, 2, path = true)),
                Triple(ELIXIR_APPLICATION, Signature("get_all_env", 1), Shape(0, null)),
                Triple(ERLANG_APPLICATION, Signature("get_env", 1), Shape(null, 0)),
                Triple(ERLANG_APPLICATION, Signature("get_env", 2), Shape(0, 1)),
                Triple(ERLANG_APPLICATION, Signature("get_env", 3), Shape(0, 1)),
                Triple(ERLANG_APPLICATION, Signature("get_all_env", 1), Shape(0, null)),
            ).groupBy({ it.second }, { it.first to it.third })
                .mapValues { (_, reads) -> Reads(reads.map { it.second }.distinct().single(), reads.map { it.first }) }

        private val MAX_ARITY = SHAPES.keys.maxOf { it.arity }
    }
}
