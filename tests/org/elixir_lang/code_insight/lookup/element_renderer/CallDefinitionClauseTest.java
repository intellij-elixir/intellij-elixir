package org.elixir_lang.code_insight.lookup.element_renderer;

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.codeInsight.lookup.LookupElementPresentation;
import com.intellij.psi.PsiElement;
import org.elixir_lang.junit.LightTestCase;
import org.elixir_lang.psi.call.Call;
import org.jetbrains.annotations.NotNull;

public class CallDefinitionClauseTest extends LightTestCase {
    /*
     * Tests
     */

    public void testIssue457ShorterName() {
        String name = "fo";
        LookupElement lookupElement = lookupElement(name);
        LookupElementPresentation lookupElementPresentation = new LookupElementPresentation();

        lookupElement.renderElement(lookupElementPresentation);

        assertEquals(name, lookupElementPresentation.getItemText());
    }

    public void testIssue457EqualName() {
        String name = "foo";
        LookupElement lookupElement = lookupElement(name);
        LookupElementPresentation lookupElementPresentation = new LookupElementPresentation();

        lookupElement.renderElement(lookupElementPresentation);

        assertEquals(name, lookupElementPresentation.getItemText());
    }

    public void testIssue457LongerNameIssue503() {
        String name = "fooo";
        LookupElement lookupElement = lookupElement(name);
        LookupElementPresentation lookupElementPresentation = new LookupElementPresentation();

        lookupElement.renderElement(lookupElementPresentation);

        assertEquals(name, lookupElementPresentation.getItemText());
    }

    /*
     * Protected Instance Methods
     */

    @Override
    protected String getTestDataPath() {
        return "testData/org/elixir_lang/code_insight/lookup/element_renderer/call_definition_clause";
    }

    /*
     * Private Instance Methods
     */

    private LookupElement lookupElement(@NotNull String name) {
        myFixture.configureByFile("issue_457.ex");

        PsiElement elementAtCaret = myFixture.getFile().findElementAt(myFixture.getCaretOffset());

        assertNotNull(elementAtCaret);

        PsiElement maybeDefElement = elementAtCaret.getParent().getParent();

        assertInstanceOf(maybeDefElement, Call.class);

        Call maybeDefCall = (Call) maybeDefElement;

        assertTrue(org.elixir_lang.psi.CallDefinitionClause.is(maybeDefCall));

        return LookupElementBuilder
                .createWithSmartPointer(name, maybeDefElement)
                .withRenderer(new CallDefinitionClause(name));
    }
}
