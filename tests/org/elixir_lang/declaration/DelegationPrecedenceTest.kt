package org.elixir_lang.declaration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DelegationPrecedenceTest {
    private data class Reached(val name: String, val delegation: Boolean, val ownDoc: Boolean = false)

    private val delegation = Reached("delegation", delegation = true)
    private val documentedDelegation = Reached("documented delegation", delegation = true, ownDoc = true)
    private val target = Reached("target", delegation = false)
    private val documentedTarget = Reached("documented target", delegation = false, ownDoc = true)

    private fun standsIn(narrowed: List<Reached>) = DelegationPrecedence.standsIn(narrowed, Reached::delegation)

    private fun documented(reached: List<Reached>, otherwise: Reached? = null) =
        DelegationPrecedence.documented(reached, Reached::delegation, Reached::ownDoc) { otherwise }

    @Test
    fun nothingDoesNotStandIn() {
        assertFalse(standsIn(emptyList()))
    }

    @Test
    fun delegationsAloneDoNotStandIn() {
        assertFalse(standsIn(listOf(delegation, documentedDelegation)))
    }

    @Test
    fun aDefinitionStandsIn() {
        assertTrue(standsIn(listOf(target)))
    }

    @Test
    fun aDefinitionBesideADelegationStandsIn() {
        assertTrue(standsIn(listOf(delegation, target)))
    }

    @Test
    fun aDelegationWithItsOwnDocIsDocumentedBeforeWhatComesFirst() {
        assertEquals(documentedDelegation, documented(listOf(target, documentedDelegation), otherwise = target))
    }

    @Test
    fun theFirstDocumentedDelegationIsDocumented() {
        val second = Reached("second documented delegation", delegation = true, ownDoc = true)

        assertEquals(documentedDelegation, documented(listOf(delegation, documentedDelegation, second)))
    }

    @Test
    fun aDelegationWithoutItsOwnDocLeavesItToOtherwise() {
        assertEquals(target, documented(listOf(delegation, target), otherwise = target))
    }

    @Test
    fun aDocumentedDefinitionIsNotADelegationsDoc() {
        assertEquals(target, documented(listOf(documentedTarget), otherwise = target))
    }

    @Test
    fun nothingReachedIsWhatOtherwiseSays() {
        assertEquals(target, documented(emptyList(), otherwise = target))
    }
}
