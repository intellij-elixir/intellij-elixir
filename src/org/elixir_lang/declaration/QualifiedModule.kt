package org.elixir_lang.declaration

/** Whether the module a qualified call's qualifier names is held where the call is. */
sealed interface QualifiedModule {
    /**
     * A copy of the module is held, where the source that answered found it: only that source's
     * [CandidateSource.modulars] maps it to the module's PSI.
     */
    abstract class Held : QualifiedModule {
        /** Why the module held may not be the one Elixir's env names there, `null` where it is. */
        abstract val uncertain: Uncertain?
    }

    /** No copy of the module is held. */
    data object Absent : QualifiedModule

    /** The source can't say, which never means no module is held. */
    data object Unknown : QualifiedModule
}
