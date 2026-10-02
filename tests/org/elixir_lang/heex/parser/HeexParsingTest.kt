package org.elixir_lang.heex.parser

import com.intellij.psi.templateLanguages.OuterLanguageElement
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.psi.ElixirTypes

/**
 * Multi-root parsing fixtures covering constructs beyond plain HTML: `<%= %>`, an `<% %>` block
 * spanning tags, `{}` in body/attribute/root position, local/remote components, a `<:slot>` tag,
 * and an HTML comment. See [testAttributeFollowingBraceAttribute] for the
 * `<.tag one={""} class=""/>` case - it parses cleanly here; see that test's doc comment for why
 * that matters for anyone about to change the outer-language insertion machinery.
 */
class HeexParsingTest : HeexParsingTestCase() {
    fun testEexExpression() {
        doTest(true)
    }

    fun testEexBlockSpanningTags() {
        doTest(true)
    }

    fun testBraceInBody() {
        doTest(true)
    }

    fun testBraceInAttribute() {
        doTest(true)
    }

    fun testBraceInRootPosition() {
        doTest(true)
    }

    fun testLocalComponent() {
        doTest(true)
    }

    fun testRemoteComponent() {
        doTest(true)
    }

    fun testSlot() {
        doTest(true)
    }

    fun testHtmlComment() {
        doTest(true)
    }

    /**
     * A `{}`-valued attribute immediately followed by another attribute. Without replacement text
     * for the `{""}` outer range, the HTML lexer's unquoted-attribute-value rule would run on
     * into `class=""`; [HeexParsingTestCase] registers the production patcher so that it doesn't.
     */
    fun testAttributeFollowingBraceAttribute() {
        doTest(true)
    }

    /**
     * `<%# a comment %>` between two tags produces no output, so `TemplateData.isInsertionToken`
     * does not flag it as an insertion - unlike `testEexExpression`'s `<%= @x %>`, no placeholder
     * text is spliced into the HTML source before the HTML parser runs; the comment tag's outer
     * element is reinserted from the original source afterward, same as any other outer range.
     */
    fun testNonOutputTagBetweenTags() {
        doTest(true)
    }

    /**
     * `<%!-- a comment --%>` between two tags: same shape as [testNonOutputTagBetweenTags], but with
     * the long-comment marker (`!--`/`--%>`) rather than the short one (`#`).
     */
    fun testLongComment() {
        doTest(true)
    }

    /** No golden shows the leaf class: a plain leaf and an outer-language one both print as `PsiElement(EEx Data)`. */
    fun testEexDataLeavesAreOuterLanguageElements() {
        val file = createPsiFile(getTestName(false), "<div>\n  <%= @x %>\n</div>\n")
        val elixirRoot = checkNotNull(file.viewProvider.getPsi(ElixirLanguage)) { "Expected an Elixir root in ${file.name}" }
        val eexDataLeaves = PsiTreeUtil.collectElements(elixirRoot) { it.node?.elementType == ElixirTypes.EEX_DATA }

        assertTrue("Expected at least one EEx Data leaf in: ${elixirRoot.text}", eexDataLeaves.isNotEmpty())
        for (leaf in eexDataLeaves) {
            assertTrue(
                "Expected EEx Data leaf '${leaf.text}' to be an OuterLanguageElement, was ${leaf::class.java.name}",
                leaf is OuterLanguageElement
            )
        }
    }
}
