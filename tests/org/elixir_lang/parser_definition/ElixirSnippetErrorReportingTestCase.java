package org.elixir_lang.parser_definition;

import com.ericsson.otp.erlang.OtpErlangAtom;
import com.ericsson.otp.erlang.OtpErlangTuple;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInspection.InspectionProfileEntry;
import com.intellij.codeInspection.LocalInspectionEP;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.ThrowableRunnable;
import junit.framework.Test;
import junit.framework.TestSuite;
import org.elixir_lang.inspection.KeywordPairColonInsteadOfTypeOperator;
import org.elixir_lang.inspection.KeywordsNotAtEnd;
import org.elixir_lang.inspection.MatchOperatorInsteadOfTypeOperator;
import org.elixir_lang.inspection.NoParenthesesManyStrict;
import org.elixir_lang.inspection.NoParenthesesStrict;
import org.elixir_lang.intellij_elixir.Quoter;
import org.elixir_lang.language_level.ElixirLanguageLevel;
import org.elixir_lang.language_level.ElixirLanguageLevelResolver;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * One test per snippet of Elixir's own tests, from {@code elixir_snippets/snippets.jsonl}, that the reference quoter
 * rejects: highlighting it must report an error, from the parser, an annotator or an inspection of syntax. Inspections
 * that resolve names are left off, since an unresolved name would pass a snippet whose real error goes unreported.
 */
public class ElixirSnippetErrorReportingTestCase extends BasePlatformTestCase {
    private static final Path UNREPORTED =
            Path.of("testData", "org", "elixir_lang", "parser_definition", "unreported_errors.tsv");

    private final String hash;
    private final String source;
    private final String rejection;
    private final KnownFailures knownFailures;

    private ElixirSnippetErrorReportingTestCase(@NotNull JsonObject snippet,
                                                @NotNull String source,
                                                @NotNull String rejection,
                                                @NotNull KnownFailures knownFailures) {
        hash = snippet.get("hash").getAsString();
        this.source = source;
        this.rejection = rejection;
        this.knownFailures = knownFailures;

        JsonObject origin = snippet.getAsJsonObject("origin");
        setName(hash + " " + origin.get("file").getAsString() + ":" + origin.get("line").getAsInt());
    }

    public static Test suite() throws IOException {
        TestSuite suite = new TestSuite(ElixirSnippetErrorReportingTestCase.class.getName());
        KnownFailures knownFailures = KnownFailures.forElixirUnderTest(UNREPORTED);
        List<String> hashes = new ArrayList<>();

        for (String line : Files.readAllLines(ElixirSnippetParsingTestCase.SNIPPETS, StandardCharsets.UTF_8)) {
            JsonObject snippet = JsonParser.parseString(line).getAsJsonObject();
            String source = ElixirSnippetParsingTestCase.source(snippet);
            OtpErlangTuple quoted;

            try {
                quoted = Quoter.INSTANCE.quote(source);
            } catch (Throwable e) {
                suite.addTest(TestSuite.warning("The reference quoter could not judge the snippets: " + e));
                return suite;
            }

            if (quoted == null) {
                suite.addTest(TestSuite.warning("The reference quoter did not answer for snippet " + snippet.get("hash")));
                return suite;
            }

            if (!"ok".equals(((OtpErlangAtom) quoted.elementAt(0)).atomValue())) {
                ElixirSnippetErrorReportingTestCase test =
                        new ElixirSnippetErrorReportingTestCase(snippet, source, Quoter.rejection(quoted), knownFailures);
                suite.addTest(test);
                hashes.add(test.hash);
            }
        }

        knownFailures.checkStale(suite, hashes);

        return suite;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        ElixirLanguageLevelResolver.overrideLanguageLevel(getProject(), ElixirLanguageLevel.of(System.getenv("ELIXIR_VERSION")));
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            ElixirLanguageLevelResolver.overrideLanguageLevel(getProject(), null);
        } catch (Throwable e) {
            addSuppressedException(e);
        } finally {
            super.tearDown();
        }
    }

    @Override
    protected void runBare(@NotNull ThrowableRunnable<Throwable> testRunnable) throws Throwable {
        if (knownFailures.contains(hash)) {
            super.runBare(() -> knownFailures.expectFailure(hash, this::assertErrorReported));
        } else {
            super.runBare(this::assertErrorReported);
        }
    }

    private void assertErrorReported() {
        myFixture.enableInspections(inspections());
        myFixture.configureByText("snippet.ex", source);

        List<HighlightInfo> highlights = myFixture.doHighlighting();

        if (highlights.stream().noneMatch(highlight -> highlight.getSeverity().compareTo(HighlightSeverity.ERROR) >= 0)) {
            String reported = highlights
                    .stream()
                    .filter(highlight -> highlight.getDescription() != null)
                    .map(highlight ->
                            highlight.getSeverity() + " at " + highlight.getStartOffset() + ".." + highlight.getEndOffset() +
                                    ": " + highlight.getDescription()
                    )
                    .collect(Collectors.joining("; "));

            fail("Elixir " + System.getenv("ELIXIR_VERSION") + " " + rejection + ", but the plugin reports no error" +
                    (reported.isEmpty() ? "" : ", only " + reported));
        }
    }

    private static InspectionProfileEntry @NotNull [] inspections() {
        List<InspectionProfileEntry> inspections = new ArrayList<>(List.of(
                new KeywordPairColonInsteadOfTypeOperator(),
                new KeywordsNotAtEnd(),
                new MatchOperatorInsteadOfTypeOperator(),
                new NoParenthesesManyStrict(),
                new NoParenthesesStrict()
        ));
        // Enabled by default in every profile; its implementation class is internal API, so it is found by name.
        inspections.add(
                LocalInspectionEP.LOCAL_INSPECTION
                        .getExtensionList()
                        .stream()
                        .filter(inspection -> "NonAsciiCharacters".equals(inspection.shortName))
                        .findFirst()
                        .orElseThrow()
                        .instantiateTool()
        );

        return inspections.toArray(InspectionProfileEntry[]::new);
    }
}
