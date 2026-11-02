package org.elixir_lang.declaration

import org.elixir_lang.declaration.Applicability.*
import org.elixir_lang.declaration.ArityKnowledge.*
import org.elixir_lang.declaration.Reach.*
import org.elixir_lang.declaration.RejectedCall.Named
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RejectedCallTest {
    private fun candidate(
        arity: ArityKnowledge = Exact(2),
        reach: Reach = OWN,
        applicability: Applicability = WRONG_ARITY,
        definer: Definer = Definer.DEF,
        offset: Int = 0
    ) = Candidate(declaration("f", arity, definer, offset), reach, applicability)

    private val everyArity: (Candidate, Int) -> Boolean = { _, _ -> true }

    private fun named(
        vararg candidates: Candidate,
        remote: Boolean = false,
        admits: (Candidate, Int) -> Boolean
    ): List<Named<Candidate>>? = RejectedCall.named(candidates.toList(), { it }, remote, admits)

    @Test
    fun aWrongArityCandidateIsNamedWithItsArity() {
        val wrong = candidate()

        assertEquals(listOf(Named(wrong, listOf(2))), named(wrong, admits = everyArity))
    }

    @Test
    fun defaultsAreEachArityTheyAccept() {
        val defaults = candidate(Range(1, 3))

        assertEquals(listOf(Named(defaults, listOf(1, 2, 3))), named(defaults, admits = everyArity))
    }

    @Test
    fun theAritiesAreThoseAdmitted() {
        val imported = candidate(Range(1, 2), IMPORT)

        assertEquals(listOf(Named(imported, listOf(2))), named(imported) { _, arity -> arity == 2 })
    }

    @Test
    fun aCandidateNoArityOfWhichIsAdmittedIsNotNamed() {
        assertEquals(emptyList<Named<Candidate>>(), named(candidate(reach = IMPORT)) { _, _ -> false })
    }

    @Test
    fun anOpenHeadAcceptsEveryArityFromItsMinimumWhatEverIsAdmitted() {
        val open = candidate(Open(3))

        assertEquals(listOf(Named(open, emptyList(), from = 3)), named(open) { _, _ -> false })
    }

    @Test
    fun aValidCandidateMeansTheCallIsNotRejected() {
        assertNull(
            named(candidate(), candidate(Exact(1), applicability = VALID, offset = 1), admits = everyArity)
        )
    }

    @Test
    fun anOpaqueCandidateMeansRejectionCannotBeDecided() {
        assertNull(
            named(candidate(), candidate(Unknown, applicability = OPAQUE, offset = 1), admits = everyArity)
        )
    }

    @Test
    fun aRemoteCallWithNothingExportedToNameIsStillRejected() {
        assertEquals(
            emptyList<Named<Candidate>>(),
            named(candidate(definer = Definer.DEFP), remote = true, admits = everyArity)
        )
    }

    @Test
    fun aPrivateCandidateIsNotNamed() {
        val wrong = candidate()

        assertEquals(
            listOf(Named(wrong, listOf(2))),
            named(
                wrong,
                candidate(Exact(1), applicability = PRIVATE, definer = Definer.DEFP, offset = 1),
                admits = everyArity
            )
        )
    }

    @Test
    fun whatADelegationDelegatesToIsNotNamed() {
        for (reach in listOf(DELEGATION_TARGET, UNHELD_DELEGATION_TARGET)) {
            assertEquals(
                reach.name,
                emptyList<Named<Candidate>>(),
                named(candidate(reach = reach), admits = everyArity)
            )
        }
    }

    @Test
    fun aLocalCallNamesWhatEveryOtherReachBringsIn() {
        for (reach in Reach.entries.filterNot { it.delegationTarget }) {
            val wrong = candidate(reach = reach, definer = Definer.DEFP)

            assertEquals(reach.name, listOf(Named(wrong, listOf(2))), named(wrong, admits = everyArity))
        }
    }

    @Test
    fun aRemoteCallNamesOnlyWhatTheModuleExports() {
        val all = Reach.entries.flatMap { reach -> Definer.entries.map { candidate(reach = reach, definer = it) } }

        assertEquals(
            all.filter { it.reach.held && it.declaration.capabilities.public },
            all.filter { named(it, remote = true, admits = everyArity).orEmpty().isNotEmpty() }
        )
    }

    @Test
    fun eachNearMissIsNamedInOrder() {
        val first = candidate(Exact(2), offset = 1)
        val second = candidate(Exact(3), USE, offset = 2)

        assertEquals(
            listOf(Named(first, listOf(2)), Named(second, listOf(3))),
            named(first, second, admits = everyArity)
        )
    }
}
