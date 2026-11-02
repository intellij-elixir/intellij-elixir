package org.elixir_lang.ui.code_intelligence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RowVerdictTest {
    private val answer = Answer.Is("string.ex: def split(string, pattern, options \\\\ [])")
    private val right = Reading.Read("string.ex: def split(string, pattern, options \\\\ [])")
    private val wrong = Reading.Read("Elixir.String.beam:2085")
    private val owner = Known("#4602", Reading.Read("Elixir.String.beam:2085"))
    private val notStarted = Reading.NotStarted("RenameElement didn't run")
    private val ownerNotStarted = Known("#4578", Reading.NotStarted("RenameElement didn't run; the last action was null"))

    @Test
    fun `a right answer with no known failure passes`() =
        assertEquals(Verdict.Pass, verdict(answer, null, right))

    @Test
    fun `a wrong answer with no known failure fails`() =
        assertEquals(
            Verdict.Fail("expected string.ex: def split(string, pattern, options \\\\ []), read Elixir.String.beam:2085"),
            verdict(answer, null, wrong),
        )

    @Test
    fun `not started with no known failure fails`() =
        assertEquals(Verdict.Fail("not started: RenameElement didn't run"), verdict(answer, null, notStarted))

    @Test
    fun `a wrong answer with a known failure is expected, with its owner`() =
        assertEquals(Verdict.Known("#4602", "Elixir.String.beam:2085"), verdict(answer, owner, wrong))

    @Test
    fun `a wrong answer other than the one recorded is still the known failure`() =
        assertEquals(Verdict.Known("#4602", "Elixir.String.beam:2086"), verdict(answer, owner, Reading.Read("Elixir.String.beam:2086")))

    @Test
    fun `not started with a known failure that records it is expected`() =
        assertEquals(Verdict.Known("#4578", "not started: RenameElement didn't run"), verdict(answer, ownerNotStarted, notStarted))

    @Test
    fun `not started with a known failure that records an answer fails`() =
        assertEquals(
            Verdict.Fail("not started: RenameElement didn't run, where #4602's known failure reads Elixir.String.beam:2085"),
            verdict(answer, owner, notStarted),
        )

    @Test
    fun `a right answer with a known failure fails as fixed`() =
        assertEquals(Verdict.Fail("fixed: drop the known failure (#4602)"), verdict(answer, owner, right))

    @Test
    fun `an answer of items holds when the reading lists every one`() =
        assertEquals(Verdict.Pass, verdict(Answer.Includes("assign", "assign_new"), null, Reading.Read("assigns\nassign_new\nassign")))

    @Test
    fun `an answer of items fails when the reading lacks one`() =
        assertEquals(
            Verdict.Fail("expected items including assign, assign_new, read assigns\nassign"),
            verdict(Answer.Includes("assign", "assign_new"), null, Reading.Read("assigns\nassign")),
        )
}
