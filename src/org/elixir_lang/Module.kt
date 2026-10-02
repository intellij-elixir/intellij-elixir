package org.elixir_lang

import com.intellij.openapi.util.Condition
import org.elixir_lang.code.InspectAtom
import org.elixir_lang.psi.call.name.Module.ELIXIR_PREFIX
import org.elixir_lang.utils.ElixirModulesUtil
import org.jetbrains.annotations.Contract
import kotlin.collections.List

object Module {
    private const val SEPARATOR = "."

    class IsNestedUnder (moduleName: String) : Condition<String> {
        private val splitModuleName: List<String> = split(moduleName)

        override fun value(maybeStartsWithModuleName: String): Boolean {
            val splitMaybeStartsWithModuleName = split(maybeStartsWithModuleName)
            var isNestedUnder = true

            if (splitMaybeStartsWithModuleName.size > splitModuleName.size) {
                for (i in splitModuleName.indices) {
                    if (splitMaybeStartsWithModuleName[i] != splitModuleName[i]) {
                        isNestedUnder = false

                        break
                    }
                }
            } else {
                isNestedUnder = false
            }

            return isNestedUnder
        }
    }

    /**
     * The name Elixir gives the module [atom] names, which is the name it is indexed by: the alias of an `Elixir.` atom
     * that `inspect` writes as an alias, otherwise `:` and the atom.
     */
    @Contract(pure = true)
    @JvmStatic
    fun indexName(atom: String): String = inspectedAlias(atom) ?: ":$atom"

    /** The alias `inspect` writes for [atom] when it is an `Elixir.` atom shaped as an alias; otherwise `null`. */
    @Contract(pure = true)
    @JvmStatic
    fun inspectedAlias(atom: String): String? =
        atom
            .takeIf { it.startsWith(ELIXIR_PREFIX) }
            ?.substring(ELIXIR_PREFIX.length)
            ?.takeIf { ElixirModulesUtil.elixirAliasSegmentsRegex.matches(it) }

    /**
     * The alias [relative] nested in the module [parentIndexName], composed as `elixir_aliases:concat/1` does: `Inner`
     * in `:foo` is `:"Elixir.foo.Inner"`. When either has no value, so is the result, which [atom] reads as `null`.
     *
     * Any other parent is joined, which for an alias is `concat/1`'s answer, and keeps a placeholder such as `P.[X, Y]`
     * from being read as an atom.
     */
    @Contract(pure = true)
    @JvmStatic
    fun nest(parentIndexName: String, relative: String): String {
        val parentAtom = nonElixirAtom(parentIndexName)

        return when {
            parentAtom == null -> "$parentIndexName$SEPARATOR$relative"
            NO_VALUE in split(relative) -> "$parentAtom$SEPARATOR$relative"
            else -> {
                val head = if (parentAtom == ELIXIR || parentAtom.startsWith(ELIXIR_PREFIX)) {
                    parentAtom
                } else {
                    "$ELIXIR_PREFIX${parentAtom.removePrefix(SEPARATOR)}"
                }

                indexName("$head$SEPARATOR$relative")
            }
        }
    }

    private const val ELIXIR = "Elixir"

    /** Stands in an index name for a name, or an alias in it, that has no value. */
    const val NO_VALUE = "?"

    /** The atom [indexName] encodes, the inverse of [indexName]; `null` when it stands for a name with no value. */
    @Contract(pure = true)
    @JvmStatic
    fun atom(indexName: String): String? =
        nonElixirAtom(indexName) ?: indexName.takeUnless { NO_VALUE in split(it) }?.let { ELIXIR_PREFIX + it }

    /** The module [indexName] names, as Elixir writes it; a name with no value as it is. */
    @Contract(pure = true)
    @JvmStatic
    fun inspect(indexName: String): String = atom(indexName)?.let(InspectAtom::literal) ?: indexName

    /** The atom [indexName] encodes when it is not an `Elixir.` atom. */
    private fun nonElixirAtom(indexName: String): String? = indexName.takeIf { it.startsWith(":") }?.substring(1)

    /**
     * Emulates Module.concat/1
     */
    @Contract(pure = true)
    @JvmStatic
    fun concat(aliases: Collection<String>): String = aliases.joinToString(SEPARATOR)

    /**
     * Emulates Module.split/1
     */
    @Contract(pure = true)
    @JvmStatic
    fun split(name: String): List<String> = name.split(SEPARATOR)

    fun prefix(name: String): List<String> = split(name).dropLast(1)

    fun isRelative(ancestors: List<String>, descendant: String): Boolean = relative(ancestors, descendant).isNotEmpty()

    fun relative(ancestors: List<String>, descendant: String): List<String> {
        val descendants = split(descendant)
        return relative(ancestors, descendants)
    }

    fun relative(ancestors: List<String>, descendants: List<String>): List<String> {
        return if (ancestors.size < descendants.size &&
                ancestors.zip(descendants).all { (ancestor, descendent) -> ancestor == descendent }) {
            descendants.subList(ancestors.size, descendants.size)
        } else {
            emptyList()
        }
    }
}
