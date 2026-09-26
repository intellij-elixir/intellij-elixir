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

    /** What a `defdelegate` it holds delegates to. */
    DELEGATION_TARGET,

    /** What a `defdelegate` it does not hold - one it imports, or one in a module it is nested in - delegates to. */
    UNHELD_DELEGATION_TARGET,

    /** Declared in a module it is nested in. */
    OUTER,

    /** Brought in by an explicit `import`. */
    IMPORT,

    /** Brought in by the implicit `import Kernel` and `import Kernel.SpecialForms`. */
    IMPLICIT_IMPORT;

    /** What the module itself holds: what it declares or a `use` injects, public or not. [exports] adds visibility. */
    val held: Boolean get() = this == OWN || this == USE

    /** What a remote use of the module can reach: what it holds, and what a delegation it holds delegates to. */
    val remote: Boolean get() = held || this == DELEGATION_TARGET

    /** What a `defdelegate` delegates to, which a use of the delegation does not name. */
    val delegationTarget: Boolean get() = this == DELEGATION_TARGET || this == UNHELD_DELEGATION_TARGET

    companion object {
        private val KEY = Key<Reach>("Reach")

        /**
         * Whether the module exports [element], reached by [reach]: it holds it, and it is public. At [runtime], as for
         * `apply/3` or an MFA tuple, only a function.
         */
        @RequiresReadLock
        fun exports(reach: Reach, element: PsiElement, runtime: Boolean = false): Boolean =
            reach.held && callableFromAnotherModule(element, runtime)

        /**
         * Whether a remote use of the module - a qualified call, or at [runtime] `apply/3` or an MFA tuple - reaches
         * [element], reached by [reach]: what it [exports], or what a delegation among those delegates to, if public.
         */
        @RequiresReadLock
        fun remotelyReaches(reach: Reach, element: PsiElement, runtime: Boolean): Boolean =
            reach.remote && callableFromAnotherModule(element, runtime)

        private fun callableFromAnotherModule(element: PsiElement, runtime: Boolean): Boolean =
            CallableDeclaration.capabilitiesOf(element, ResolveState.initial())
                ?.let { it.public && (!runtime || it.runtimeFunction) } == true

        /**
         * Whether a call names [element], reached by [reach]: never what a delegation delegates to, which the call does
         * not name, though resolution follows it; for a `remote` call, only what the module [exports]. Asked for a call
         * that compiles as well as one that does not.
         */
        @RequiresReadLock
        fun named(reach: Reach, element: PsiElement, remote: Boolean): Boolean =
            !reach.delegationTarget && (!remote || exports(reach, element))

        /**
         * [this] state, reached through [reach] as well - an `import`, `use` or `defdelegate` at [via] - when the path to
         * [via] is [held]; otherwise it stays how [via] was reached, what a delegation delegates to [UNHELD_DELEGATION_TARGET].
         */
        fun ResolveState.reachedThrough(reach: Reach, via: PsiElement? = null): ResolveState {
            val reachedVia = via?.let { of(it, this) } ?: get(KEY) ?: OWN

            return put(
                KEY,
                when {
                    reachedVia.held -> reach
                    reach == DELEGATION_TARGET && !reachedVia.delegationTarget -> UNHELD_DELEGATION_TARGET
                    else -> reachedVia
                }
            )
        }

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
