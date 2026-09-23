package org.elixir_lang.psi.scope

import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.beam.psi.Module as BeamModule
import org.elixir_lang.psi.CallableDeclaration
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.ElixirPsiImplUtil.ENTRANCE
import org.elixir_lang.psi.stub.type.call.Stub.isModular

/** How resolution reached a declaration from the module it started in. */
enum class Reach {
    /** Declared in that module. */
    OWN,

    /** Injected into it by a `use`. */
    USE,

    /** What a `defdelegate` in it delegates to. */
    DELEGATION_TARGET,

    /** Declared in a module it is nested in. */
    OUTER,

    /** Brought in by an explicit `import`. */
    IMPORT,

    /** Brought in by the implicit `import Kernel` and `import Kernel.SpecialForms`. */
    IMPLICIT_IMPORT;

    /** What the module itself holds: what it declares or a `use` injects, public or not. [exports] adds visibility. */
    val exported: Boolean get() = this == OWN || this == USE

    /** What a remote call of the module reaches: what it exports, and what a delegation among those delegates to. */
    val remote: Boolean get() = exported || this == DELEGATION_TARGET

    companion object {
        private val KEY = Key<Reach>("Reach")

        /** Whether the module exports [element], reached by [reach]: it holds it, and it is public. */
        @RequiresReadLock
        fun exports(reach: Reach, element: PsiElement): Boolean =
            reach.exported && CallableDeclaration.capabilitiesOf(element, ResolveState.initial())?.public == true

        /**
         * Whether a call that does not compile names [element], reached by [reach]: never what a delegation delegates to,
         * which the call does not name, though resolution follows it ([remote]); for a [remote] call, only what the module
         * [exports].
         */
        @RequiresReadLock
        fun named(reach: Reach, element: PsiElement, remote: Boolean): Boolean =
            reach != DELEGATION_TARGET && (!remote || exports(reach, element))

        /** [this] state, reached through [reach] as well; a path that is not [exported] stays what it was. */
        fun ResolveState.reachedThrough(reach: Reach): ResolveState =
            if ((get(KEY) ?: OWN).exported) put(KEY, reach) else this

        /** [this] state for what a `defdelegate` calls: a delegation target however the delegation itself was reached. */
        fun ResolveState.reachedAsDelegationTarget(): ResolveState = put(KEY, DELEGATION_TARGET)

        /**
         * How [element] was reached with [state]: what the path says, or [OUTER] for a declaration outside the module
         * resolution started in.
         */
        fun of(element: PsiElement, state: ResolveState): Reach {
            val reach = state.get(KEY) ?: OWN
            val home = if (reach == OWN) state.get(ENTRANCE)?.let(::homeModular) else null

            return if (home != null && !PsiTreeUtil.isAncestor(home, element, false)) OUTER else reach
        }

        private fun homeModular(entrance: PsiElement): PsiElement? =
            generateSequence(entrance) { it.parent }.firstOrNull { it is BeamModule || (it is Call && isModular(it)) }
    }
}
