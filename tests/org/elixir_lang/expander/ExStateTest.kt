package org.elixir_lang.expander

import org.elixir_lang.expander.ExState.Prematch.OutsideMatch
import org.elixir_lang.expander.ExState.Prematch.OutsideMatch.Mode.Raise
import org.elixir_lang.expander.ExState.Prematch.OutsideMatch.Mode.Warn
import org.elixir_lang.expander.ExState.Write.NotWriting
import org.elixir_lang.expander.ExState.Write.Writing
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `elixir_env`'s read and write halves of the variables. How versions are allocated is asserted through the clauses in
 * [ExpanderTest].
 */
class ExStateTest {
    @Test
    fun `the empty state reads nothing, writes nothing and allocates from 0`() {
        assertEquals(ExState(emptyMap(), NotWriting, OutsideMatch(Raise), 0), EMPTY)
    }

    @Test
    fun `outside a match an undefined variable is a local call before 1_15 and an error from 1_15`() {
        assertEquals(OutsideMatch(Warn), ExState.empty(ElixirLanguageLevel.of("1.14.5")).prematch)
        assertEquals(OutsideMatch(Raise), ExState.empty(ElixirLanguageLevel.of("1.15.0-rc.0")).prematch)
    }

    @Test
    fun `prepare_write opens a write half holding the read half`() {
        val state = EMPTY.copy(read = mapOf(X to 0))

        assertEquals(state.copy(write = Writing(mapOf(X to 0))), state.prepareWrite())
    }

    @Test
    fun `reset_read takes the read half from the scope's start and keeps the writes`() {
        val start = EMPTY.copy(read = mapOf(X to 0))
        val after = start.copy(read = mapOf(X to 0, Y to 1), write = Writing(mapOf(X to 0, Y to 1)), version = 2)

        assertEquals(after.copy(read = mapOf(X to 0)), after.resetRead(start))
    }

    @Test
    fun `close_write outside a write scope makes the writes readable`() {
        val upper = EMPTY.copy(read = mapOf(X to 0))
        val inner = upper.copy(read = mapOf(X to 0), write = Writing(mapOf(X to 0, Y to 1)), version = 2)

        assertEquals(inner.copy(read = mapOf(X to 0, Y to 1), write = NotWriting), inner.closeWrite(upper))
    }

    @Test
    fun `close_write inside a write scope merges the writes into it, keeping the higher version`() {
        val upper = EMPTY.copy(write = Writing(mapOf(X to 3, Y to 0)))
        val inner = upper.copy(write = Writing(mapOf(X to 1, Y to 2)), version = 4)

        assertEquals(
            inner.copy(read = mapOf(X to 1, Y to 2), write = Writing(mapOf(X to 3, Y to 2))),
            inner.closeWrite(upper)
        )
    }

    private companion object {
        val X = Variable("x", Variable.NIL)
        val Y = Variable("y", Variable.NIL)
        val EMPTY = ExState.empty(ElixirLanguageLevel.of("1.20.4"))
    }
}
