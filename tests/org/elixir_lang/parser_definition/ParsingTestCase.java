package org.elixir_lang.parser_definition;

import com.ericsson.otp.erlang.OtpErlangObject;
import com.intellij.lang.ParserDefinition;
import com.intellij.openapi.util.text.LineColumn;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.*;
import org.elixir_lang.ElixirLanguage;
import org.elixir_lang.ElixirParserDefinition;
import org.elixir_lang.intellij_elixir.Quoter;
import org.elixir_lang.psi.impl.ElixirPsiImplUtil;
import org.elixir_lang.language_level.ElixirLanguageLevel;
import org.elixir_lang.language_level.ElixirLanguageLevelResolver;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

/**
 * Created by kadie.enheduanna.inanna on 8/7/14.
 */
public abstract class ParsingTestCase extends com.intellij.testFramework.ParsingTestCase {
    public ParsingTestCase() {
        this("ex", new ElixirParserDefinition());
    }

    protected ParsingTestCase(String extension, ParserDefinition... parserDefinitions) {
        super("", extension, parserDefinitions);
    }

    /**
     * Quotes in the language level of the Elixir the reference quoter is running, so both sides of
     * {@link #assertQuotedCorrectly()} speak the same version.
     *
     * These are light fixtures with no Elixir SDK, so production resolution would reach
     * {@link ElixirLanguageLevel#getFALLBACK()} on every CI leg and every leg would compare against the same
     * language level however old the Elixir it ran. {@code ELIXIR_VERSION} is exported to the test JVM by
     * the build, from the SDK it resolved - the same SDK the quoter was built against.
     *
     * Absent - running a test straight from the IDE, outside the build's environment -
     * {@link ElixirLanguageLevel#of} answers {@link ElixirLanguageLevel#getFALLBACK()}, which is what production
     * resolves to when no Elixir SDK is configured.
     *
     * <p>The override is installed either way, and has to be: skipping it sends
     * {@code ElixirLanguageLevelResolver.languageLevelFor} into the module model, and this fixture's mock
     * project has no {@code ProjectFileIndex} for {@code ModuleUtilCore.findModuleForPsiElement} to
     * find, so every test in this hierarchy dies on a {@code @NotNull} assertion naming neither
     * Elixir nor the language level.
     */
    @Override
    protected void setUp() throws Exception {
        super.setUp();

        ElixirLanguageLevelResolver.overrideLanguageLevel(getProject(), ElixirLanguageLevel.of(System.getenv("ELIXIR_VERSION")));
    }

    protected void assertParsedAndQuotedAroundError() {
        assertParsedAndQuotedAroundError(true);
    }

    protected void assertParsedAndQuotedAroundError(boolean checkResult) {
        doTest(checkResult);
        assertQuotedAroundError();
    }

    protected void assertParsedAndQuotedCorrectly() {
        assertParsedAndQuotedCorrectly(true);
    }

    protected void assertParsedAndQuotedCorrectly(boolean checkResult) {
        doTest(checkResult);
        assertWithoutLocalError();
        assertQuotedCorrectly();
    }

    /**
     * For a construct the parser accepts in every version, but that the reference quoter started
     * rejecting as invalid Elixir from {@code languageLevel} on - so only one side of the boundary can be
     * asserted per run, chosen by the language level the leg's real Elixir resolves to (set in
     * {@link #setUp()}), not by which Elixir wrote the test.
     */
    protected void assertParsedAndQuotedCorrectlyBefore(ElixirLanguageLevel languageLevel) {
        assertParsedAndQuotedCorrectlyBefore(languageLevel, true);
    }

    protected void assertParsedAndQuotedCorrectlyBefore(ElixirLanguageLevel languageLevel, boolean checkResult) {
        doTest(checkResult);

        if (ElixirLanguageLevelResolver.languageLevelFor(myFile).getElixir()
                .compareTo(languageLevel.getElixir()) >= 0) {
            assertQuotedAroundError();
        } else {
            assertWithoutLocalError();
            assertQuotedCorrectly();
        }
    }

    /** Mirror of {@link #assertParsedAndQuotedCorrectlyBefore}: the quoter rejects it *below* {@code languageLevel}. */
    protected void assertParsedAndQuotedCorrectlyFrom(ElixirLanguageLevel languageLevel) {
        assertParsedAndQuotedCorrectlyFrom(languageLevel, true);
    }

    protected void assertParsedAndQuotedCorrectlyFrom(ElixirLanguageLevel languageLevel, boolean checkResult) {
        doTest(checkResult);

        if (ElixirLanguageLevelResolver.languageLevelFor(myFile).getElixir().compareTo(languageLevel.getElixir()) < 0) {
            assertQuotedAroundError();
        } else {
            assertWithoutLocalError();
            assertQuotedCorrectly();
        }
    }

    /**
     * As {@link #assertParsedAndQuotedCorrectly}, where the parser, like the reference quoter, rejects it below
     * {@code languageLevel}; the tree is only checked from {@code languageLevel}, since below it has an error in it.
     */
    protected void assertParsedAndQuotedCorrectlyFromOrParsedWithErrors(
            ElixirLanguageLevel languageLevel,
            boolean checkResult
    ) throws IOException {
        doTest(false);

        if (ElixirLanguageLevelResolver.languageLevelFor(myFile).getElixir().compareTo(languageLevel.getElixir()) < 0) {
            assertWithLocalError();
            Quoter.assertError(myFile);
        } else {
            if (checkResult) {
                checkResult(getTestName(), myFile);
            }

            assertWithoutLocalError();
            assertQuotedCorrectly();
        }
    }

    /**
     * As {@link #assertParsedAndQuotedAroundError}: every supported version rejects, but below
     * {@code languageLevel} it raises {@code expectedException} rather than answering an error tuple.
     */
    protected void assertParsedAndQuotedAroundErrorOrRaise(
            ElixirLanguageLevel languageLevel,
            String expectedException
    ) {
        assertParsedAndQuotedAroundErrorOrRaise(languageLevel, expectedException, true);
    }

    protected void assertParsedAndQuotedAroundErrorOrRaise(
            ElixirLanguageLevel languageLevel,
            String expectedException,
            boolean checkResult
    ) {
        doTest(checkResult);
        assertQuotedAroundErrorOrRaise(languageLevel, expectedException);
    }

    /**
     * As {@link #assertParsedAndQuotedCorrectlyBefore}, where the releases from {@code languageLevel} reject by raising
     * {@code expectedException} until {@code errorLanguageLevel}, and by answering an error tuple from it.
     */
    protected void assertParsedAndQuotedCorrectlyBeforeOrRaise(
            ElixirLanguageLevel languageLevel,
            ElixirLanguageLevel errorLanguageLevel,
            String expectedException,
            boolean checkResult
    ) {
        doTest(checkResult);

        if (ElixirLanguageLevelResolver.languageLevelFor(myFile).getElixir()
                .compareTo(languageLevel.getElixir()) >= 0) {
            assertQuotedAroundErrorOrRaise(errorLanguageLevel, expectedException);
        } else {
            assertWithoutLocalError();
            assertQuotedCorrectly();
        }
    }

    private void assertQuotedAroundErrorOrRaise(ElixirLanguageLevel languageLevel, String expectedException) {
        assertInstanceOf(ElixirPsiImplUtil.quote(myFile), OtpErlangObject.class);

        if (ElixirLanguageLevelResolver.languageLevelFor(myFile).getElixir().compareTo(languageLevel.getElixir()) < 0) {
            Quoter.assertRaise(myFile, expectedException);
        } else {
            Quoter.assertError(myFile);
        }
    }

    protected void assertParsedWithErrors() {
        assertParsedWithErrors(true);
    }

    protected void assertParsedWithErrors(boolean checkResult) {
        doTest(checkResult);

        assertWithLocalError();
        Quoter.assertError(myFile);
    }

    private List<PsiElement> localErrors() {
        final FileViewProvider fileViewProvider = myFile.getViewProvider();
        PsiFile root = fileViewProvider.getPsi(ElixirLanguage.INSTANCE);
        final List<PsiElement> errorElementList = new LinkedList<>();

        assertNotNull(root);
        root.accept(
                new PsiRecursiveElementWalkingVisitor() {
                    @Override
                    public void visitElement(@NotNull PsiElement element) {
                        if (element instanceof PsiErrorElement) {
                            errorElementList.add(element);
                        }

                        super.visitElement(element);
                    }
                }
        );

        return errorElementList;
    }

    protected void assertWithLocalError() {

        List<PsiElement> errorElementList = localErrors();

        assertFalse("No PsiErrorElements found in parsed file PSI", errorElementList.isEmpty());
    }

    protected void assertWithoutLocalError() {

        List<PsiElement> errorElementList = localErrors();

        if (!errorElementList.isEmpty()) {
            PsiErrorElement first = (PsiErrorElement) errorElementList.get(0);
            LineColumn lineColumn = StringUtil.offsetToLineColumn(myFile.getText(), first.getTextOffset());

            fail(errorElementList.size() + " PsiErrorElements found in parsed file PSI, the first on line " +
                    (lineColumn.line + 1) + " in column " + (lineColumn.column + 1) + ": " +
                    first.getErrorDescription());
        }
    }

    protected void assertQuotedAroundError() {
        assertInstanceOf(ElixirPsiImplUtil.quote(myFile), OtpErlangObject.class);
        Quoter.assertError(myFile);
    }

    protected void assertQuotedCorrectly() {
        Quoter.assertQuotedCorrectly(myFile);
    }

    @Override
    protected String getTestDataPath() {
        return "testData/org/elixir_lang/parser_definition";
    }

    /**
     * Whether test is running on travis-ci.
     *
     * @return {@code true} if on Travis CI; {@code false} otherwise
     */
    protected boolean isNotTravis() {
        String travis = System.getenv("TRAVIS");

        return travis == null || !travis.equals("true");
    }

    @Override
    protected boolean includeRanges() {
        return true;
    }

}
