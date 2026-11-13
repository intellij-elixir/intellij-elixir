package org.elixir_lang.code_insight.completion.contributor;

import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementPresentation;
import org.elixir_lang.PlatformTestCase;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class CallDefinitionClauseTest extends PlatformTestCase {
    /*
     * Tests
     */

    public void testPrivateFunctionExcludedFromRemoteCompletion() {
        myFixture.configureByFiles("private_function_usage.ex", "private_function_declaration.ex");
        myFixture.complete(CompletionType.BASIC, 1);
        // The private_function* defp clauses must not leak into a remote (qualified) call's completion.
        assertCompletionOffersExactly(myFixture.getLookupElementStrings(), "public_function1", "public_function2");
    }

    public void testPublicFunction() {
        myFixture.configureByFiles("public_function_usage.ex", "public_function_declaration.ex");
        myFixture.complete(CompletionType.BASIC, 1);
        assertCompletionOffersExactly(myFixture.getLookupElementStrings(), "public_function1", "public_function2");
    }

    public void testPrivateMacroExcludedFromRemoteCompletion() {
        myFixture.configureByFiles("private_macro_usage.ex", "private_macro_declaration.ex");
        myFixture.complete(CompletionType.BASIC, 1);
        // The private_macro* defmacrop clauses must not leak into a remote (qualified) call's completion.
        assertCompletionOffersExactly(myFixture.getLookupElementStrings(), "public_macro1", "public_macro2");
    }

    public void testPublicMacroFunction() {
        myFixture.configureByFiles("public_macro_usage.ex", "public_macro_declaration.ex");
        myFixture.complete(CompletionType.BASIC, 1);
        assertCompletionOffersExactly(myFixture.getLookupElementStrings(), "public_macro1", "public_macro2");
    }

    public void testPrivateGuardExcludedFromRemoteCompletion() {
        myFixture.configureByFiles("private_guard_usage.ex", "private_guard_declaration.ex");
        myFixture.complete(CompletionType.BASIC, 1);
        // The private_guard* defguardp clauses must not leak into a remote (qualified) call's completion.
        assertCompletionOffersExactly(myFixture.getLookupElementStrings(), "public_guard1", "public_guard2");
    }

    public void testPublicGuard() {
        myFixture.configureByFiles("public_guard_usage.ex", "public_guard_declaration.ex");
        myFixture.complete(CompletionType.BASIC, 1);
        assertCompletionOffersExactly(myFixture.getLookupElementStrings(), "public_guard1", "public_guard2");
    }

    public void testMixesWithNestedModules() {
        myFixture.configureByFiles("mixed_usage.ex", "mixed_declaration.ex");
        myFixture.complete(CompletionType.BASIC, 1);
        // Only the public macro/function and the nested module; the defmacrop/defp are excluded.
        assertCompletionOffersExactly(myFixture.getLookupElementStrings(), "public_macro", "public_function", "Nested");
    }

    public void testIssue2122() {
        myFixture.configureByFiles("defdelegate.ex", "to.ex");
        myFixture.complete(CompletionType.BASIC, 1);

        assertCompletionOffersExactly(myFixture.getLookupElementStrings(), "source", "source_as", "usage");

        LookupElement[] lookupElements = myFixture.getLookupElements();

        LookupElementPresentation lookupElementPresentation = new LookupElementPresentation();
        assertNotNull(lookupElements);
        lookupElements[0].renderElement(lookupElementPresentation);
        assertEquals("source", lookupElementPresentation.getItemText());
        // A delegation is presented like any other declaration: head parameters, then location.
        assertEquals("() (defdelegate.ex defmodule Defdelegate)", lookupElementPresentation.getTailText());

        lookupElementPresentation = new LookupElementPresentation();
        lookupElements[1].renderElement(lookupElementPresentation);
        assertEquals("source_as", lookupElementPresentation.getItemText());
        assertEquals("() (defdelegate.ex defmodule Defdelegate)", lookupElementPresentation.getTailText());

        lookupElementPresentation = new LookupElementPresentation();
        lookupElements[2].renderElement(lookupElementPresentation);
        assertEquals("usage", lookupElementPresentation.getItemText());
        assertEquals(" (defdelegate.ex defmodule Defdelegate)", lookupElementPresentation.getTailText());
    }

    public void testExecuteOnEExFunctionFrom() {
        myFixture.configureByFiles("eex_function.ex", "eex.ex");
        myFixture.complete(CompletionType.BASIC, 1);

        assertCompletionOffersExactly(myFixture.getLookupElementStrings(), "function_from_file_sample", "function_from_string_sample");

        LookupElement[] lookupElements = myFixture.getLookupElements();

        LookupElementPresentation lookupElementPresentation = new LookupElementPresentation();
        assertNotNull(lookupElements);
        lookupElements[0].renderElement(lookupElementPresentation);
        assertEquals("function_from_file_sample", lookupElementPresentation.getItemText());
        assertEquals(" (eex_function.ex defmodule EExFunction)", lookupElementPresentation.getTailText());

        lookupElementPresentation = new LookupElementPresentation();
        lookupElements[1].renderElement(lookupElementPresentation);
        assertEquals("function_from_string_sample", lookupElementPresentation.getItemText());
        assertEquals(" (eex_function.ex defmodule EExFunction)", lookupElementPresentation.getTailText());
    }

    public void testExecuteOnException() {
        myFixture.configureByFile("exception.ex");
        myFixture.complete(CompletionType.BASIC, 1);

        assertCompletionOffersExactly(myFixture.getLookupElementStrings(), "extra", "exception", "message");

        LookupElement[] lookupElements = myFixture.getLookupElements();

        LookupElementPresentation lookupElementPresentation = new LookupElementPresentation();
        assertNotNull(lookupElements);
        lookupElements[0].renderElement(lookupElementPresentation);
        assertEquals("exception", lookupElementPresentation.getItemText());
        assertEquals("(msg)", lookupElementPresentation.getTailText());

        lookupElementPresentation = new LookupElementPresentation();
        lookupElements[1].renderElement(lookupElementPresentation);
        assertEquals("extra", lookupElementPresentation.getItemText());
        assertEquals(" (exception.ex defmodule MyException)", lookupElementPresentation.getTailText());

        lookupElementPresentation = new LookupElementPresentation();
        lookupElements[2].renderElement(lookupElementPresentation);
        assertEquals("message", lookupElementPresentation.getItemText());
        assertEquals("(exception)", lookupElementPresentation.getTailText());
    }

    public void testExecuteOnMixGeneratorEmbed() {
        myFixture.configureByFiles("mix_generator_embed.ex", "mix_generator.ex");
        myFixture.complete(CompletionType.BASIC, 1);

        assertCompletionOffersExactly(myFixture.getLookupElementStrings(), "error_text", "log_template", "usage");

        LookupElement[] lookupElements = myFixture.getLookupElements();

        LookupElementPresentation lookupElementPresentation = new LookupElementPresentation();
        assertNotNull(lookupElements);
        lookupElements[0].renderElement(lookupElementPresentation);
        assertEquals("error_text", lookupElementPresentation.getItemText());
        assertEquals("()", lookupElementPresentation.getTailText());

        lookupElementPresentation = new LookupElementPresentation();
        lookupElements[1].renderElement(lookupElementPresentation);
        assertEquals("usage", lookupElementPresentation.getItemText());
        assertEquals(" (mix_generator_embed.ex defmodule MixGeneratorEmbed)", lookupElementPresentation.getTailText());

        lookupElementPresentation = new LookupElementPresentation();
        lookupElements[2].renderElement(lookupElementPresentation);
        assertEquals("log_template", lookupElementPresentation.getItemText());
        assertEquals("(assigns)", lookupElementPresentation.getTailText());
    }

    public void testPrefersBareFunctionHeadSignature() {
        myFixture.configureByFiles("bare_head_preferred_usage.ex", "bare_head_preferred_declaration.ex");
        myFixture.complete(CompletionType.BASIC, 1);

        assertCompletionOffersExactly(myFixture.getLookupElementStrings(), "map_every");

        LookupElement mapEvery = lookupElementByItemText("map_every");
        assertNotNull("No lookup element for map_every", mapEvery);

        LookupElementPresentation lookupElementPresentation = new LookupElementPresentation();
        mapEvery.renderElement(lookupElementPresentation);
        assertEquals("map_every", lookupElementPresentation.getItemText());
        assertEquals("(enumerable, nth, fun) (/src/bare_head_preferred_declaration.ex defmodule Prefix.BareHeadPreferredDeclaration)", lookupElementPresentation.getTailText());
    }

    public void testSameNameDifferentArityCollapsedToOneCompletion() {
        myFixture.configureByFiles("same_name_different_arity_usage.ex", "same_name_different_arity_declaration.ex");
        myFixture.complete(CompletionType.BASIC, 1);

        // dup/1 and dup/2 collapse to a single "process" entry.
        assertCompletionOffersExactly(myFixture.getLookupElementStrings(), "process");
    }

    public void testFallsBackToFirstClauseWhenNoBareHeadExists() {
        myFixture.configureByFiles("no_bare_head_fallback_usage.ex", "no_bare_head_fallback_declaration.ex");
        myFixture.complete(CompletionType.BASIC, 1);

        assertCompletionOffersExactly(myFixture.getLookupElementStrings(), "normalize");

        LookupElement normalize = lookupElementByItemText("normalize");
        assertNotNull("No lookup element for normalize", normalize);

        LookupElementPresentation lookupElementPresentation = new LookupElementPresentation();
        normalize.renderElement(lookupElementPresentation);
        assertEquals("normalize", lookupElementPresentation.getItemText());
        assertEquals("(value, fallback) when is_binary(value) (/src/no_bare_head_fallback_declaration.ex defmodule Prefix.NoBareHeadFallbackDeclaration)", lookupElementPresentation.getTailText());
    }

    public void testQualifiedFunctionOfferedWhenLastStatementInBlock() {
        myFixture.configureByFiles("last_statement_usage.ex", "following_statement_declaration.ex");
        myFixture.complete(CompletionType.BASIC, 1);

        assertCompletionOffersExactly(myFixture.getLookupElementStrings(), "public_function1", "public_function2");
    }

    public void testQualifiedFunctionOfferedWhenAQualifiedCallFollows() {
        myFixture.configureByFiles("sibling_qualified_call_usage.ex", "public_function_declaration.ex");
        myFixture.complete(CompletionType.BASIC, 1);

        assertCompletionOffersExactly(myFixture.getLookupElementStrings(), "public_function1", "public_function2");
    }

    /**
     * A trailing dot binds to a following *alias*, but not to a following atom: Elixir rejects
     * {@code "Enum.\n:maps.values(%{})"} outright, so the original file's tree comes from error recovery
     * rather than from the two statements being joined.
     */
    public void testQualifiedFunctionOfferedWhenAnAtomQualifiedCallFollows() {
        myFixture.configureByFiles("atom_call_follows_usage.ex", "public_function_declaration.ex");
        myFixture.complete(CompletionType.BASIC, 1);

        assertCompletionOffersExactly(myFixture.getLookupElementStrings(), "public_function1", "public_function2");
    }

    public void testQualifiedFunctionOfferedForANestedQualifierWhenAQualifiedCallFollows() {
        myFixture.configureByFiles("nested_qualifier_usage.ex", "public_function_declaration.ex");
        myFixture.complete(CompletionType.BASIC, 1);

        assertCompletionOffersExactly(myFixture.getLookupElementStrings(), "public_function1", "public_function2");
    }

    public void testQualifiedFunctionOfferedWhenAnotherStatementFollowsInBlock() {
        myFixture.configureByFiles("following_statement_usage.ex", "following_statement_declaration.ex");
        myFixture.complete(CompletionType.BASIC, 1);

        assertCompletionOffersExactly(myFixture.getLookupElementStrings(), "public_function1", "public_function2");
    }

    /*
     * Protected Instance Methods
     */

    @Override
    protected String getTestDataPath() {
        return "testData/org/elixir_lang/code_insight/completion/contributor/call_definition_clause";
    }

    /*
     * Private Instance Methods
     */

    private LookupElement lookupElementByItemText(@NotNull String expectedItemText) {
        LookupElement[] lookupElements = myFixture.getLookupElements();
        assertNotNull("Completion lookup not shown", lookupElements);

        return Arrays.stream(lookupElements)
                .filter(lookupElement -> {
                    LookupElementPresentation presentation = new LookupElementPresentation();
                    lookupElement.renderElement(presentation);
                    return expectedItemText.equals(presentation.getItemText());
                })
                .findFirst()
                .orElse(null);
    }

    /**
     * Asserts the completion popup offers <em>exactly</em> {@code expected} - no missing entries, no
     * extras (e.g. private {@code defp}/{@code defmacrop} clauses), and no duplicates (same-name,
     * different-arity clauses must collapse to a single entry).
     */
    private void assertCompletionOffersExactly(List<String> actual, @NotNull String... expected) {
        assertNotNull("Completion lookup not shown", actual);

        List<String> actualSorted = new ArrayList<>(actual);
        Collections.sort(actualSorted);

        List<String> expectedSorted = new ArrayList<>(Arrays.asList(expected));
        Collections.sort(expectedSorted);

        assertEquals("Completion should offer exactly the expected entries", expectedSorted, actualSorted);
    }
}
