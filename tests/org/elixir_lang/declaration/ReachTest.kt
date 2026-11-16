package org.elixir_lang.declaration

import org.elixir_lang.call.Visibility
import org.elixir_lang.declaration.Reach.*
import org.junit.Assert.assertEquals
import org.junit.Test

class ReachTest {
    @Test
    fun heldIsWhatTheModuleDeclaresOrAUseInjects() {
        assertEquals(setOf(OWN, USE), Reach.entries.filter { it.held }.toSet())
    }

    @Test
    fun remoteAddsTheTargetOfAHeldDelegation() {
        assertEquals(setOf(OWN, USE, DELEGATION_TARGET), Reach.entries.filter { it.remote }.toSet())
    }

    @Test
    fun delegationTargetIsEitherTarget() {
        assertEquals(
            setOf(DELEGATION_TARGET, UNHELD_DELEGATION_TARGET),
            Reach.entries.filter { it.delegationTarget }.toSet()
        )
    }

    @Test
    fun exportsWhatItHoldsThatIsPublic() {
        assertEquals(
            cross(setOf(OWN, USE), PUBLIC),
            admitted { reach, capabilities -> reach.exports(capabilities) }
        )
    }

    @Test
    fun exportsAtRuntimeOnlyPublicFunctions() {
        assertEquals(
            cross(setOf(OWN, USE), PUBLIC_FUNCTIONS),
            admitted { reach, capabilities -> reach.exports(capabilities, runtime = true) }
        )
    }

    @Test
    fun remotelyReachesWhatItExportsTheTargetsOfItsDelegationsAndTheIndexsFinds() {
        assertEquals(
            cross(setOf(OWN, USE, DELEGATION_TARGET, INDEXED), PUBLIC),
            admitted { reach, capabilities -> reach.remotelyReaches(capabilities) }
        )
    }

    @Test
    fun remotelyReachesAtRuntimeOnlyPublicFunctions() {
        assertEquals(
            cross(setOf(OWN, USE, DELEGATION_TARGET, INDEXED), PUBLIC_FUNCTIONS),
            admitted { reach, capabilities -> reach.remotelyReaches(capabilities, runtime = true) }
        )
    }

    @Test
    fun anUndecidedVisibilityCountsAsExported() {
        assertEquals(setOf(OWN, USE), Reach.entries.filter { it.exports(UNDECIDED_FUNCTION) }.toSet())
        assertEquals(setOf(OWN, USE), Reach.entries.filter { it.exports(UNDECIDED_FUNCTION, runtime = true) }.toSet())
        assertEquals(
            setOf(OWN, USE, DELEGATION_TARGET, INDEXED),
            Reach.entries.filter { it.remotelyReaches(UNDECIDED_FUNCTION, runtime = true) }.toSet()
        )
    }

    @Test
    fun anUndecidedMacroIsNotExportedAtRuntime() {
        assertEquals(setOf(OWN, USE), Reach.entries.filter { it.exports(UNDECIDED_MACRO) }.toSet())
        assertEquals(emptySet<Reach>(), Reach.entries.filter { it.exports(UNDECIDED_MACRO, runtime = true) }.toSet())
    }

    /** Readers of `public` and `remoteCallable` ask whether a definition is literally public. */
    @Test
    fun anUndecidedVisibilityIsNotLiterallyPublic() {
        assertEquals(false, UNDECIDED_FUNCTION.public)
        assertEquals(false, UNDECIDED_FUNCTION.remoteCallable)
    }

    private fun admitted(predicate: (Reach, Capabilities) -> Boolean): Set<Pair<Reach, Definer>> =
        Reach.entries.flatMap { reach ->
            Definer.entries.filter { predicate(reach, it.capabilities) }.map { reach to it }
        }.toSet()

    private fun cross(reaches: Set<Reach>, definers: Set<Definer>): Set<Pair<Reach, Definer>> =
        reaches.flatMap { reach -> definers.map { reach to it } }.toSet()

    private companion object {
        val PUBLIC_FUNCTIONS = setOf(Definer.DEF, Definer.DEFMEMO)
        val PUBLIC = PUBLIC_FUNCTIONS + setOf(Definer.DEFMACRO, Definer.DEFGUARD)
        val UNDECIDED_FUNCTION = Capabilities(quotesArguments = false, compileTime = false, usableInGuards = false, Visibility.UNDECIDED)
        val UNDECIDED_MACRO = Capabilities(quotesArguments = true, compileTime = true, usableInGuards = false, Visibility.UNDECIDED)
    }
}
