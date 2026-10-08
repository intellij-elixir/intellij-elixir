package org.elixir_lang.intellij_elixir

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Counting needs no node, so each test owns its instance. */
class QuoterSilencesTest {
    @Test
    fun `the third silence in a row reaches the limit`() {
        val silences = QuoterSilences()

        assertFalse(silences.silent())
        assertFalse(silences.silent())
        assertTrue(silences.silent())
        assertEquals(3, silences.count)
    }

    @Test
    fun `an answer resets the count`() {
        val silences = QuoterSilences()

        silences.silent()
        silences.silent()
        silences.answered()

        assertEquals(0, silences.count)
        assertFalse(silences.silent())
        assertFalse(silences.silent())
    }
}
