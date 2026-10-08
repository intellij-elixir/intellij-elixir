package org.elixir_lang.intellij_elixir

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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

    @Test
    fun `refusing starts at the third silence in a row and ends with an answer`() {
        val silences = QuoterSilences()

        silences.silent()
        silences.silent()
        assertFalse(silences.refusing)

        silences.silent()
        assertTrue(silences.refusing)

        silences.answered()
        assertFalse(silences.refusing)
    }

    @Test
    fun `a call after three silences in a row is refused without being sent`() {
        val silences = QuoterSilences()
        repeat(QuoterSilences.LIMIT) { silences.silent() }
        var sent = 0

        assertThrows(IllegalStateException::class.java) {
            silences.guard({ throw IllegalStateException("refused") }) { sent++ }
        }

        assertEquals(0, sent)
    }

    @Test
    fun `a call that returns is not a silence`() {
        val silences = QuoterSilences()

        assertEquals("reply", silences.guard({ throw IllegalStateException("refused") }) { "reply" })
        assertEquals(0, silences.count)
    }
}
