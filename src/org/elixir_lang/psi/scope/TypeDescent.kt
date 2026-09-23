package org.elixir_lang.psi.scope

import com.intellij.psi.PsiElement
import org.elixir_lang.beam.psi.Module as BeamModule
import org.elixir_lang.beam.psi.TypeDefinition as BeamTypeDefinition
import org.elixir_lang.psi.*
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.walk.ShapeTable

/**
 * The shapes the type resolver descends into looking for type definitions and type variables, for [Type.execute].
 * Each bucket names the overload that reads the shape and, in [Bucket.reads], the type that overload casts to,
 * `PsiElement` where it does not cast.
 */
object TypeDescent {
    enum class Bucket(val reads: Class<*>) {
        /** `@` typed on the line above an existing attribute: the attribute below is read. */
        AT_OPERATION(AtOperation::class.java),
        /** A call decides by what it is: a type-spec attribute, a module or `use`. */
        CALL(Call::class.java),
        /** An anonymous function type's signature. */
        STAB_PARENTHESES_SIGNATURE(ElixirStabParenthesesSignature::class.java),
        STAB_NO_PARENTHESES_SIGNATURE(ElixirStabNoParenthesesSignature::class.java),
        /** The key of `when key: type` is a type variable. */
        PARAMETER(PsiElement::class.java),
        /** A container whose child expressions are read in turn. */
        CHILDREN(PsiElement::class.java),
        /** A shape no type is written in, and past which none will be found, so the search ends. */
        HALT(PsiElement::class.java),
        /** A decompiled module: its type definitions are read when it encloses the entrance. */
        BEAM_MODULE(BeamModule::class.java),
        BEAM_TYPE_DEFINITION(BeamTypeDefinition::class.java),
        /** A decompiled function defines no type; the search continues. */
        BEAM_CALL_DEFINITION(PsiElement::class.java),
        /** Holds no type definition, so the search continues past it. */
        PASS(PsiElement::class.java),
        /** Cannot hold an expression at all, so also passed. */
        LEAF(PsiElement::class.java)
    }

    val classifier = ShapeTable.column(Bucket.PASS) { it.typeDescent }

    fun classify(element: PsiElement): Bucket = classifier.classify(element)
}
