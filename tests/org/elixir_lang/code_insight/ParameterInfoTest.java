package org.elixir_lang.code_insight;

import com.intellij.lang.parameterInfo.CreateParameterInfoContext;
import com.intellij.testFramework.utils.parameterInfo.MockCreateParameterInfoContext;
import com.intellij.testFramework.utils.parameterInfo.MockParameterInfoUIContext;
import org.elixir_lang.declaration.ExpanderSettingTestCase;
import org.elixir_lang.psi.Arguments;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class ParameterInfoTest extends ExpanderSettingTestCase {
    public void testBareHeadPreferredOverImplementationClauses() {
        myFixture.configureByFiles("bare_head_usage.ex", "bare_head_declaration.ex");

        ParameterInfo handler = new ParameterInfo();
        CreateParameterInfoContext context = new MockCreateParameterInfoContext(myFixture.getEditor(), myFixture.getFile());
        Arguments args = handler.findElementForParameterInfo(context);

        assertNotNull("Should find Arguments element at caret", args);

        handler.showParameterInfo(args, context);
        Object[] items = context.getItemsToShow();

        assertNotNull("Should have parameter info items", items);
        assertEquals("Multiple clause heads should be deduplicated to one entry", 1, items.length);

        // Render the single item and verify it shows the bare head's canonical parameters
        Signature signature = assertInstanceOf(items[0], Signature.class);
        MockParameterInfoUIContext<Arguments> uiContext = new MockParameterInfoUIContext<>(args);
        uiContext.setCurrentParameterIndex(0);
        handler.updateUI(signature, uiContext);

        assertEquals("enumerable, nth, fun", uiContext.getText());
    }

    public void testDifferentAritiesPreservedAsSeparateHints() {
        myFixture.configureByFiles("different_arity_usage.ex", "different_arity_declaration.ex");

        ParameterInfo handler = new ParameterInfo();
        CreateParameterInfoContext context = new MockCreateParameterInfoContext(myFixture.getEditor(), myFixture.getFile());
        Arguments args = handler.findElementForParameterInfo(context);

        assertNotNull("Should find Arguments element at caret", args);

        handler.showParameterInfo(args, context);
        Object[] items = context.getItemsToShow();

        assertNotNull("Should have parameter info items", items);
        assertEquals("Different arities (process/1, process/2) should produce separate hints", 2, items.length);

        // Render both items and collect their parameter text
        List<String> paramTexts = renderedParameterTexts(handler, args, items);

        assertTrue("Should include process/1 parameters", paramTexts.contains("item"));
        assertTrue("Should include process/2 parameters", paramTexts.contains("item, opts"));
    }

    public void testLocalUnqualifiedCall() {
        myFixture.configureByFile("local_call.ex");

        ParameterInfo handler = new ParameterInfo();
        CreateParameterInfoContext context = new MockCreateParameterInfoContext(myFixture.getEditor(), myFixture.getFile());
        Arguments args = handler.findElementForParameterInfo(context);

        assertNotNull("Should find Arguments element at caret", args);

        handler.showParameterInfo(args, context);
        Object[] items = context.getItemsToShow();

        assertNotNull("Should have parameter info items", items);

        List<String> paramTexts = renderedParameterTexts(handler, args, items);
        assertEquals("Local unqualified call should show exactly one signature, got: " + paramTexts,
                List.of("augend, addend"), paramTexts);
    }

    public void testDefaultArgumentsSpanArities() {
        myFixture.configureByFiles("default_args_usage.ex", "default_args_declaration.ex");

        ParameterInfo handler = new ParameterInfo();
        CreateParameterInfoContext context = new MockCreateParameterInfoContext(myFixture.getEditor(), myFixture.getFile());
        Arguments args = handler.findElementForParameterInfo(context);

        assertNotNull("Should find Arguments element at caret", args);

        handler.showParameterInfo(args, context);
        Object[] items = context.getItemsToShow();

        assertNotNull("Should have parameter info items", items);

        // The single canonical signature is shown with the default-value operator, so the user sees the
        // optional parameter and its default rather than two separate greet/1 and greet/2 hints.
        List<String> paramTexts = renderedParameterTexts(handler, args, items);
        assertEquals("A single clause with a default argument should produce exactly one signature, got: " + paramTexts,
                List.of("name, greeting \\\\ \"Hello\""), paramTexts);
    }

    /**
     * Parameter hints are wanted exactly after the comma that begins the next argument, and that is
     * the moment the source is momentarily invalid.  The malformed argument list used to take the
     * enclosing definition with it, leaving no Arguments ancestor for the caret to sit in.
     */
    public void testTrailingCommaKeepsHint() {
        myFixture.configureByFile("trailing_comma_local_call.ex");

        ParameterInfo handler = new ParameterInfo();
        CreateParameterInfoContext context = new MockCreateParameterInfoContext(myFixture.getEditor(), myFixture.getFile());
        Arguments args = handler.findElementForParameterInfo(context);

        assertNotNull("Should find Arguments element at caret after a trailing comma", args);

        handler.showParameterInfo(args, context);
        Object[] items = context.getItemsToShow();

        assertNotNull("Should have parameter info items", items);

        List<String> paramTexts = renderedParameterTexts(handler, args, items);
        assertEquals("A trailing comma should not change which signature is shown, got: " + paramTexts,
                List.of("augend, addend"), paramTexts);
    }

    /**
     * A function whose name another function's name starts with is not described by it.  References are
     * resolved as incomplete code - which is what lets a call resolve before its arguments are typed, and
     * so is exactly the state parameter info is wanted in - and that resolves every prefix match too, so
     * {@code reduce} was described by {@code reduce_while} as well as by its own two arities.
     */
    public void testPrefixMatchedFunctionIsNotOffered() {
        myFixture.configureByFiles("prefix_match_usage.ex", "prefix_match_declaration.ex");

        ParameterInfo handler = new ParameterInfo();
        CreateParameterInfoContext context = new MockCreateParameterInfoContext(myFixture.getEditor(), myFixture.getFile());
        Arguments args = handler.findElementForParameterInfo(context);

        assertNotNull("Should find Arguments element at caret", args);

        handler.showParameterInfo(args, context);
        Object[] items = context.getItemsToShow();

        assertNotNull("Should have parameter info items", items);

        List<String> paramTexts = renderedParameterTexts(handler, args, items);
        assertEquals("Only reduce's own arities should be offered, got: " + paramTexts,
                List.of("enumerable, fun", "enumerable, acc, fun"), paramTexts);
    }

    /**
     * Renders every parameter-info item as its user-visible parameter text (as {@code updateUI}
     * would present in the popup) with the caret on the first parameter.
     *
     * <p>Returns a {@link List} (not a {@code Set}) so that duplicate rendered signatures - which
     * would indicate a duplicate-hint bug - are preserved for the caller to assert against, rather
     * than being silently collapsed.
     */
    private List<String> renderedParameterTexts(ParameterInfo handler, Arguments args, Object[] items) {
        return Arrays.stream(items)
                .map(item -> {
                    Signature signature = assertInstanceOf(item, Signature.class);
                    MockParameterInfoUIContext<Arguments> uiContext = new MockParameterInfoUIContext<>(args);
                    uiContext.setCurrentParameterIndex(0);
                    handler.updateUI(signature, uiContext);
                    return uiContext.getText();
                })
                .collect(Collectors.toList());
    }

    /*
     * Protected Instance Methods
     */

    @Override
    protected String getTestDataPath() {
        return "testData/org/elixir_lang/code_insight/parameter_info";
    }
}
