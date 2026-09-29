package org.elixir_lang.structure_view

import org.elixir_lang.junit.UnitTestCase

/**
 * The table's invariants. Pure assertions over [ChildCall.ENTRIES] - no fixture, no IDE.
 */
class ChildCallTest : UnitTestCase() {
    private fun names(entries: List<ChildCall.Entry>) = entries.map { it.name }.sorted()

    private val nonEmpty = "the table must not be empty or this assertion is vacuous"

    /**
     * Membership and order in full. Order is behaviour - it decides which entry wins for a call several
     * match - and the assertions below are relational, so a deleted or reordered entry satisfies them.
     */
    fun testTheTableIsExactlyThisInThisOrder() {
        assertEquals(
            listOf(
                "or",
                "declaration",
                "specification",
                "implementation",
                "module",
                "overridable",
                "protocol",
                "quote",
                "structure",
                "type",
                "use",
                "ex_unit describe",
                "ex_unit test",
                "call definition head",
                "unknown"
            ),
            ChildCall.ENTRIES.map { it.name }
        )
    }

    /**
     * Not covered by the order test: adding the new entry's name to that list repairs it while leaving
     * `build` reaching `unknown` first for anything with a `do` block.
     */
    fun testCatchAllStaysLast() {
        assertEquals(
            "the catch-all must be the last entry or the entries after it never build anything",
            "unknown",
            ChildCall.ENTRIES.last().name
        )
    }

    fun testEntryNamesAreUnique() {
        assertFalse(nonEmpty, ChildCall.ENTRIES.isEmpty())

        val duplicates = ChildCall.ENTRIES.groupBy { it.name }.filterValues { it.size > 1 }.keys

        assertEquals(emptySet<String>(), duplicates)
    }

    fun testEveryEntryDoesSomething() {
        assertFalse(nonEmpty, ChildCall.ENTRIES.isEmpty())

        val inert = ChildCall.ENTRIES.filter { it.handle == null && !it.suitable }.map { it.name }

        assertEquals("an entry must build a node, be claimed by isSuitable, or both", emptyList<String>(), inert)
    }

    /**
     * Building a node and being a caret-sync target are the same answer except for two entries, each
     * for a structural reason. A third means the two have drifted apart again.
     */
    fun testOnlyTheTwoDocumentedEntriesDifferBetweenConsumers() {
        val builtButNotClaimed = ChildCall.ENTRIES.filter { it.handle != null && !it.suitable }
        val claimedButNotBuilt = ChildCall.ENTRIES.filter { it.handle == null && it.suitable }

        assertEquals(
            "only `or` may be dispatched without contributing to isSuitable - it is the container the " +
                "dispatch unwraps",
            listOf("or"),
            names(builtButNotClaimed)
        )
        assertEquals(
            "only `call definition head` may be a caret target this dispatch does not build - " +
                "those elements are built as children of a CallDefinition",
            listOf("call definition head"),
            names(claimedButNotBuilt)
        )
    }
}
