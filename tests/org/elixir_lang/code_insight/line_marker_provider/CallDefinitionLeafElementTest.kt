package org.elixir_lang.code_insight.line_marker_provider

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzerSettings
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import org.elixir_lang.PlatformTestCase

/**
 * Verifies that Elixir `CallDefinition` line markers are anchored to **leaf** elements and
 * that `CallDefinition.getLineMarkerInfo` returns `null` for composite elements.
 *
 * See: [the SDK documentation](https://plugins.jetbrains.com/docs/intellij/line-marker-provider.html)
 *
 * The fixture is extracted from Elixir 1.11.3 `kernel.ex` which contains multiple `@doc`, `@spec`,
 * and `def` blocks that trigger `CallDefinition`'s method-separator logic.
 *
 * These tests verify that the leaf-element dispatch inversion is correctly implemented.
 */
class CallDefinitionLeafElementTest : PlatformTestCase() {

    override fun getTestDataPath(): String =
        "testData/org/elixir_lang/code_insight/line_marker_provider/call_definition"

    override fun setUp() {
        super.setUp()
        // Method separators must be enabled for CallDefinition to produce any LineMarkerInfo
        DaemonCodeAnalyzerSettings.getInstance().SHOW_METHOD_SEPARATORS = true
    }

    override fun tearDown() {
        try {
            DaemonCodeAnalyzerSettings.getInstance().SHOW_METHOD_SEPARATORS = false
        } finally {
            super.tearDown()
        }
    }

    /**
     * Verifies that every [LineMarkerInfo] element returned by `CallDefinition` is a leaf.
     *
     * The platform logs `Performance warning: LineMarker is supposed to be registered for leaf
     * elements only` when a non-leaf element is used. This test fails if any marker is anchored
     * to a composite element.
     */
    fun testAllLineMarkersAnchoredToLeafElements() {
        myFixture.configureByFile("leaf_element_contract.ex")

        // Run the highlighting passes - this triggers all LineMarkerProviders
        myFixture.doHighlighting()

        val document = myFixture.editor.document
        val markers: List<LineMarkerInfo<*>> =
            DaemonCodeAnalyzerImpl.getLineMarkers(document, myFixture.project)

        // We expect at least some separators for the multiple def groups
        assertTrue(
            "Expected at least one method separator line marker, but got none. " +
                "Is SHOW_METHOD_SEPARATORS enabled?",
            markers.isNotEmpty()
        )

        val nonLeafMarkers = markers.filter { marker ->
            val element = marker.element
            element != null && element.firstChild != null // non-leaf: has children
        }

        assertTrue(
            "Found ${nonLeafMarkers.size} LineMarkerInfo(s) anchored to non-leaf elements. " +
                "Elements: ${nonLeafMarkers.map { formatElement(it.element!!) }}. " +
                "All markers must be anchored to leaf (terminal) PSI elements per the " +
                "LineMarkerProvider contract.",
            nonLeafMarkers.isEmpty()
        )
    }

    /**
     * Verifies that [CallDefinition.getLineMarkerInfo] returns `null` when called with a
     * composite (non-leaf) element.
     *
     * The `LineMarkerProvider` contract states that the provider should only return marker info
     * when called with a leaf element. When the platform delivers a composite element, the
     * provider must return `null`.
     */
    fun testGetLineMarkerInfoReturnsNullForCompositeElements() {
        myFixture.configureByFile("leaf_element_contract.ex")

        val provider = CallDefinition()
        val psiFile = myFixture.file

        // Collect all composite (non-leaf) elements in the file
        val compositeElements = mutableListOf<PsiElement>()
        fun collect(element: PsiElement) {
            if (element.firstChild != null) { // composite
                compositeElements.add(element)
            }
            element.children.forEach(::collect)
        }
        collect(psiFile)

        assertTrue("Expected composite elements in fixture", compositeElements.isNotEmpty())

        val violating = compositeElements.filter { element ->
            provider.getLineMarkerInfo(element) != null
        }

        assertTrue(
            "CallDefinition.getLineMarkerInfo returned non-null for ${violating.size} composite " +
                "element(s): ${violating.take(5).map { formatElement(it) }}. " +
                "The provider must return null for non-leaf elements.",
            violating.isEmpty()
        )
    }

    /**
     * Verifies that `CallDefinition` still produces method separators when given leaf elements.
     * This guards against the fix breaking the separator functionality.
     */
    fun testMethodSeparatorsStillProducedFromLeafElements() {
        myFixture.configureByFile("leaf_element_contract.ex")

        val provider = CallDefinition()
        val psiFile = myFixture.file

        // Collect all leaf elements using firstChild/nextSibling traversal
        // (PsiElement.children may not include LeafPsiElement tokens)
        val leafElements = mutableListOf<PsiElement>()
        fun collect(element: PsiElement) {
            if (element.firstChild == null) { // leaf
                leafElements.add(element)
            } else {
                var child: PsiElement? = element.firstChild
                while (child != null) {
                    collect(child)
                    child = child.nextSibling
                }
            }
        }
        collect(psiFile)

        val markersFromLeaves = leafElements.mapNotNull { leaf ->
            provider.getLineMarkerInfo(leaf)
        }

        assertTrue(
            "Expected at least one method separator from leaf elements in the fixture, but got none. " +
                "The leaf-element dispatch must still find enclosing Call/AtUnqualifiedNoParenthesesCall " +
                "ancestors and produce separators.",
            markersFromLeaves.isNotEmpty()
        )

        // All markers produced should be anchored at leaves
        markersFromLeaves.forEach { marker ->
            val element = marker.element
            assertNotNull("LineMarkerInfo.element is null", element)
            assertTrue(
                "LineMarkerInfo anchored at non-leaf: ${formatElement(element!!)}",
                element is LeafPsiElement || element.firstChild == null
            )
        }
    }

    private fun formatElement(element: PsiElement): String =
        "${element.node.elementType} (${element.javaClass.simpleName}) text='${element.text.take(30)}'"

    /** `@ doc` and `@ spec` are the attributes `@doc` and `@spec`, so they group with their definition as those do. */
    fun testSpaceAfterAtGroupsDocAndSpecWithTheirDefinition() {
        val definitions = "def a, do: 1\n\n%s\n%s\ndef b, do: 2\n\n%s\ndef c, do: 3\n"

        val plain = markerLines(definitions.format("@doc \"b\"", "@spec b() :: integer", "@doc \"c\""))

        assertTrue("the unspaced definitions get no separator, so this test checks nothing", plain.isNotEmpty())
        assertEquals(plain, markerLines(definitions.format("@ doc \"b\"", "@ spec b() :: integer", "@ doc \"c\"")))
    }

    private fun markerLines(body: String): List<Int> {
        myFixture.configureByText("grouped.ex", "defmodule M do\n" + body.prependIndent("  ") + "end\n")
        myFixture.doHighlighting()

        val document = myFixture.editor.document

        return DaemonCodeAnalyzerImpl.getLineMarkers(document, myFixture.project)
            .map { document.getLineNumber(it.startOffset) }
            .sorted()
    }
}
