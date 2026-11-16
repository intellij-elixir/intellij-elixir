package org.elixir_lang.documentation

import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.util.text.StringUtil
import org.elixir_lang.declaration.ExpanderSettingTestCase

/** The line Quick Documentation shows above an entry the expander could not confirm, as users read it. */
internal const val PLAIN_LINE = "The expander could not confirm that this is the definition used here."

/**
 * Base class for Quick Documentation (Ctrl+Q) tests that drive the IDE's real documentation
 * pipeline the way a user does - by resolving the documentation target for the current caret
 * through the platform's target-selection chain and rendering it with the registered
 * documentation provider - rather than hand-resolving a [com.intellij.psi.PsiReference] in the test.
 *
 * `IdeDocumentationTargetProvider.documentationTargets` runs the exact offset-based chain Ctrl+Q
 * uses (custom documentation element -> `TargetElementUtil` -> reference at offset, bridged to a
 * [com.intellij.platform.backend.documentation.DocumentationTarget]). Because the test never
 * resolves references itself, it keeps guarding the *user-visible* behaviour across resolution
 * refactors (e.g. relocating or replacing the classic call reference) as long as the user still
 * sees the correct documentation - it locks behaviour, not implementation.
 */
abstract class QuickDocumentationTestCase : ExpanderSettingTestCase() {
    /** @see quickDocumentationAtCaret */
    protected fun quickDocumentationAtCaret(): String? = myFixture.quickDocumentationAtCaret(project)

    /** Quick Documentation at the caret in [text], opened as [fileName]; empty where there is none. */
    protected fun quickDocumentationIn(fileName: String, text: String): String {
        myFixture.configureByText(fileName, text)

        return quickDocumentationAtCaret().orEmpty()
    }

    /** The lines of [documentation]'s first definition block, unescaped. */
    protected fun definition(documentation: String): List<String> =
        documentation
            .substringAfter(DocumentationMarkup.DEFINITION_START)
            .substringBefore(DocumentationMarkup.DEFINITION_END)
            .lines()
            .filter(String::isNotEmpty)
            .map(StringUtil::unescapeXmlEntities)

    /** The title of [documentation]'s first definition block: its first line that is not the module line. */
    protected fun titleOf(documentation: String): String = definition(documentation).first { !it.startsWith("<i>module</i>") }

    /** An EEx function and a `def` of the same name, documented as "Renders with one." and "Renders with two.", are both listed, each linked from the header. */
    protected fun assertBothArities(documentation: String) {
        assertTrue("Expected the EEx function's @doc, got: $documentation", "Renders with one." in documentation)
        assertTrue("Expected the def's @doc, got: $documentation", "Renders with two." in documentation)
        assertEquals("Expected a link per arity, got: $documentation", 2, linkedIds(documentation).distinct().size)
    }
}
