package org.elixir_lang.declaration

import org.elixir_lang.call.Visibility

/** How resolution reached a declaration from the module a use is in. */
enum class Reach {
    /** Declared in that module. */
    OWN,

    /** Injected into it by a macro it calls, as `use` does. */
    USE,

    /** What a `defdelegate` it holds delegates to, where a remote call of the target's module reaches the target. */
    DELEGATION_TARGET,

    /** Any other target of a `defdelegate`. */
    UNHELD_DELEGATION_TARGET,

    /** Reached through a module it is nested in. */
    OUTER,

    /** Brought into scope by an `import`, or by a macro as an `import` would. */
    IMPORT,

    /** Brought in by the implicit `import Kernel` and `import Kernel.SpecialForms`. */
    IMPLICIT_IMPORT,

    /** Found by the name index alone, where the expansion can't say what reaches the use. */
    INDEXED;

    /** What the module itself holds, public or not. */
    val held: Boolean get() = this == OWN || this == USE

    /** What a remote use of the module reaches: what it holds, and each [DELEGATION_TARGET]. */
    val remote: Boolean get() = held || this == DELEGATION_TARGET

    /** What a `defdelegate` delegates to, which a use of the delegation does not name. */
    val delegationTarget: Boolean get() = this == DELEGATION_TARGET || this == UNHELD_DELEGATION_TARGET

    /** Whether the module exports a declaration with [capabilities]. At [runtime], as for `apply/3`, only a function. */
    fun exports(capabilities: Capabilities, runtime: Boolean = false): Boolean =
        held && callableFromAnotherModule(capabilities, runtime)

    /** Whether a remote use of the module reaches a declaration with [capabilities]. */
    fun remotelyReaches(capabilities: Capabilities, runtime: Boolean = false): Boolean =
        remote && callableFromAnotherModule(capabilities, runtime)

    // An undecided visibility counts as public, so that the near misses of a call it may serve are still reported.
    private fun callableFromAnotherModule(capabilities: Capabilities, runtime: Boolean): Boolean =
        when {
            capabilities.visibility == Visibility.UNDECIDED -> !runtime || capabilities.runtimeFunction
            runtime -> capabilities.remoteCallable
            else -> capabilities.public
        }
}
