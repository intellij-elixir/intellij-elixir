package org.elixir_lang.model.psi.function

import com.intellij.model.Pointer
import com.intellij.openapi.util.TextRange
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiFile
import org.elixir_lang.model.psi.ElixirRenameTarget
import java.util.*

/**
 * The rename target of a use of [name] at [arity] that no function can take, which every [NearMiss] of the use shares,
 * so a rename at it is refused, naming the arities that can be called there: each of [arities], and every arity from
 * [from] up. [file] and [range] are the use's name.
 */
@Suppress("UnstableApiUsage")
class WrongArityUse(
    override val file: PsiFile,
    override val range: TextRange,
    val name: String,
    val arity: Int,
    val arities: List<Int>,
    val from: Int?
) : ElixirRenameTarget {
    override val searchText: String get() = name

    override val targetName: String get() = name

    override fun useRefusal(): String {
        val callable = arities.map { "$name/$it" } + listOfNotNull(from?.let { "$name/$it and up" })
        val listed = if (callable.size == 1) callable.single() else "${callable.dropLast(1).joinToString(", ")} and ${callable.last()}"

        return "no $name/$arity can be called here; $listed can"
    }

    override fun presentation(): TargetPresentation = TargetPresentation.builder("$name/$arity").presentation()

    override fun createPointer(): Pointer<out WrongArityUse> {
        val name = this.name
        val arity = this.arity
        val arities = this.arities
        val from = this.from

        return Pointer.fileRangePointer(file, range) { restoredFile, restoredRange ->
            WrongArityUse(restoredFile, restoredRange, name, arity, arities, from)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is WrongArityUse && other.name == name && other.arity == arity && other.arities == arities && other.from == from

    override fun hashCode(): Int = Objects.hash(name, arity, arities, from)

    override fun toString(): String = "WrongArityUse($name/$arity)"

    companion object {
        /** The target of a use in [file] at [range] whose named heads take [arities] and every arity from each of [froms] up. */
        fun of(file: PsiFile, range: TextRange, name: String, arity: Int, arities: List<Int>, froms: List<Int>): WrongArityUse {
            val from = froms.minOrNull()

            return WrongArityUse(file, range, name, arity, arities.filter { from == null || it < from }.distinct().sorted(), from)
        }
    }
}
