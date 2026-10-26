package org.elixir_lang.model.psi.config

import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.lowering.AtomName
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.ElixirList
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.call.CallImpl
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.stripAccessExpressionAndParentheses
import org.elixir_lang.psi.operation.Infix

/** A read of [app]'s first-level [key], written at [element]: the key's argument, or the whole call for [WrittenKey.All]. */
class Read(val app: ReadApp, val key: WrittenKey, val element: PsiElement) {
    companion object {
        /**
         * The read [call] makes through `Application` or `:application`, or `null` when it reads no key it can name: it is
         * not a read, or neither its app nor its key is written as a literal.
         */
        @RequiresReadLock
        fun of(call: Call): Read? {
            val name = call.functionName() ?: return null
            val arity = call.resolvedFinalArity()
            val shape = SHAPES[Signature(name, arity)]?.firstOrNull { (module, _) -> call.isCalling(module, name, arity) }?.second
                ?: return null
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

            return Read(app, key, element).takeUnless {
                app == ConfigIndexKey.AnyApp && (key == WrittenKey.Unknown || key == WrittenKey.All)
            }
        }

        /** [call]'s arguments, its piped value first, each stripped of its access expression and parentheses. */
        @RequiresReadLock
        internal fun arguments(call: Call): List<PsiElement> {
            val own = call.finalArguments().orEmpty().map { it.stripAccessExpressionAndParentheses() }

            return if (CallImpl.isPipedInto(call)) {
                listOfNotNull((call.parent as Infix).leftOperand()?.stripAccessExpressionAndParentheses()) + own
            } else {
                own
            }
        }

        private data class Signature(val name: String, val arity: Int)

        /** Which argument is the app (`null`: the caller's, so any) and which the key (`null`: every key). */
        private data class Shape(val app: Int?, val key: Int?, val path: Boolean = false)

        private const val APPLICATION = "Application"
        private const val ERLANG_APPLICATION = ":application"

        private val SHAPES: Map<Signature, List<Pair<String, Shape>>> =
            listOf(
                Triple(APPLICATION, Signature("get_env", 2), Shape(0, 1)),
                Triple(APPLICATION, Signature("get_env", 3), Shape(0, 1)),
                Triple(APPLICATION, Signature("fetch_env", 2), Shape(0, 1)),
                Triple(APPLICATION, Signature("fetch_env!", 2), Shape(0, 1)),
                Triple(APPLICATION, Signature("compile_env", 2), Shape(0, 1, path = true)),
                Triple(APPLICATION, Signature("compile_env", 3), Shape(0, 1, path = true)),
                Triple(APPLICATION, Signature("compile_env", 4), Shape(1, 2, path = true)),
                Triple(APPLICATION, Signature("compile_env!", 2), Shape(0, 1, path = true)),
                Triple(APPLICATION, Signature("compile_env!", 3), Shape(1, 2, path = true)),
                Triple(APPLICATION, Signature("get_all_env", 1), Shape(0, null)),
                Triple(ERLANG_APPLICATION, Signature("get_env", 1), Shape(null, 0)),
                Triple(ERLANG_APPLICATION, Signature("get_env", 2), Shape(0, 1)),
                Triple(ERLANG_APPLICATION, Signature("get_env", 3), Shape(0, 1)),
                Triple(ERLANG_APPLICATION, Signature("get_all_env", 1), Shape(0, null)),
            ).groupBy({ it.second }, { it.first to it.third })
    }
}
