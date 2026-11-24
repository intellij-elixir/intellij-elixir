package org.elixir_lang.declaration

/** Whether the module a qualified call's qualifier names is held where the call is. */
sealed interface QualifiedModule {
    /** A copy of the module is held. */
    data object Held : QualifiedModule

    /** No copy of the module is held. */
    data object Absent : QualifiedModule

    /** The source can't say, which never means no module is held. */
    data object Unknown : QualifiedModule
}
