package org.elixir_lang.beam;

import com.google.common.io.Files;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess;
import com.intellij.psi.PsiCompiledFile;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import org.elixir_lang.PlatformTestCase;
import org.elixir_lang.golden.CommittedGolden;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class DecompilerTest extends PlatformTestCase {
    private static final String REGENERATE =
            "./gradlew test --tests org.elixir_lang.beam.DecompilerTest -PoverwriteTestData=true";

    /*
     * Tests
     */

    public void testIssue575() throws IOException {
        assertDecompiled("Issue575");
    }

    public void testIssue672() throws IOException {
        assertDecompiled("rebar3_hex_config");
    }

    public void testIssue683() throws IOException {
        assertDecompiled("orber_ifr");
    }

    public void testIssue703() throws IOException {
        assertDecompiled("Elixir.LDAPEx.ELDAPv3");
    }

    public void testIssue772() throws IOException {
        assertDecompiled("OTP20/Elixir.Kernel");
    }

    public void testIssue803() throws IOException {
        assertDecompiled("certifi_cacerts");
    }

    public void testIssue833() throws IOException {
        assertDecompiled("docgen_xmerl_xml_cb");
    }

    public void testIssue859() throws IOException {
        assertDecompiled("erl_syntax");
    }

    public void testIssue860() throws IOException {
        assertDecompiled("OTP-PUB-KEY");
    }

    public void testIssue878() throws IOException {
        assertDecompiled("gl");
    }

    public void testIssue883() throws IOException {
        assertDecompiled("fprof");
    }

    public void testElixir_1_5_0() throws IOException {
        assertDecompiled("OTP20/Elixir.AtU8Test");
    }

    public void testDocsElixirKernel() throws IOException {
        assertDecompiled("Docs/Elixir.Kernel");
    }

    public void testDocsElixirKernelSpecialForms() throws IOException {
        assertDecompiled("Docs/Elixir.Kernel.SpecialForms");
    }

    public void testDocsElixirRuntimeError() throws IOException {
        assertDecompiled("Docs/Elixir.RuntimeError");
    }

    public void testDocsErlang() throws IOException {
        assertDecompiled("Docs/erlang");
    }

    public void testIssue1882() throws IOException {
        assertDecompiled("Elixir.Ecto.Query");
    }

    public void testIssue1886() throws IOException {
        assertDecompiled("Elixir.Module");
    }

    public void testHyphenInKeywordKeys() throws IOException {
        assertDecompiled("Elixir.Phoenix.HTML.Tag");
    }

    public void testIssue2221() throws IOException {
        assertDecompiled("queue");
    }

    // Issues 2251 and 2263
    public void testSSHOptions() throws IOException {
        assertDecompiled("ssh_options");
    }

    public void testIssue2257() throws IOException {
        assertDecompiled("hipe_icode_call_elim");
    }

    // Issues 2285 and 2286
    public void testASN1CT() throws IOException {
        assertDecompiled("asn1ct");
    }

    public void testIssue2287() throws IOException {
        assertDecompiled("diameter_gen_acct_rfc6733");
    }

    public void testIssue2288() throws IOException {
        assertDecompiled("diameter_gen_base_accounting");
    }

    public void testIssue2289() throws IOException {
        assertDecompiled("diameter_gen_relay");
    }

    public void testIssue2306() throws IOException {
        assertDecompiled("dialyzer_callgraph");
    }

    public void testCode() throws IOException {
        assertDecompiled("code");
    }

    public void testIssue2328() throws IOException {
        assertDecompiled("dbg_wx_trace_win");
    }

    public void testIssue2329() throws IOException {
        assertDecompiled("ex_cursor");
    }

    public void testIssue2331() throws IOException {
        assertDecompiled("gb_sets");
    }

    public void testIssue2332() throws IOException {
        assertDecompiled("idna");
    }

    public void testIssue2401() throws IOException {
        assertDecompiled("elixir/1.13.0/Elixir.Kernel");
    }

    public void testIssue2403() throws IOException {
        assertDecompiled("Elixir.EExTestWeb.PageController");
    }

    public void testIssue2410() throws IOException {
        assertDecompiled("Elixir.Ecto.Changeset");
    }

    public void testIssue2386() throws IOException {
        assertDecompiled("Elixir.RabbitMq.Handler");
    }

    public void testIssue2976() throws IOException {
        assertDecompiled("inet_db");
    }

    // Map comprehension (`mc`, `#{K => V || ...}`): must render `into: %{}` with a `{key, value}` body.
    public void testMapComprehension() throws IOException {
        assertDecompiled("map_comprehension");
    }

    // OTP-28 zip generator (`zip`, `A <- As && B <- Bs`): must render via `Enum.zip/1`.
    public void testZipGenerator() throws IOException {
        assertDecompiled("zip_generator");
    }

    // Both together, mirroring the `asn1ct_check.create_map_value/2` SDK-sweep failure.
    public void testZipMapComprehension() throws IOException {
        assertDecompiled("zip_map_comprehension");
    }

    // A map generator INSIDE a zip (`K := V <- M && X <- L`). Distinct from the three cases above:
    // the zipped generator's pattern is a map_field_exact, not an ordinary pattern, so rendering it
    // verbatim produces `{k => v, x}`, which is only valid inside a `%{}` literal and does not parse
    // in the zip's tuple pattern.
    public void testZipMapGenerator() throws IOException {
        assertDecompiled("zip_map_generator");
    }

    // OTP-28 strict generators (`<:-` / `<:=`): list, bitstring, and map variants must render as `<-`.
    public void testStrictGenerators() throws IOException {
        assertDecompiled("strict_generators");
    }

    // OTP-25 `maybe ... [else ...] end` (EEP 49): must render as Elixir `with`.
    public void testMaybeExpr() throws IOException {
        assertDecompiled("maybe_expr");
    }

    // Erlang types whose names are reserved Elixir words (e.g. `-type nil()`) have no valid Elixir
    // `@type`; they must be preserved as comments so the source stays parseable.
    public void testReservedTypeName() throws IOException {
        assertDecompiled("reserved_type");
    }

    // Erlang named funs (`fun F(...) -> ... end`): render as a plain `fn` (with a caveat comment),
    // both when bound to a variable and when immediately invoked.
    public void testNamedFun() throws IOException {
        assertDecompiled("named_fun");
    }

    /*
     * Instance Methods
     */

    /*
     * Protected Instance Methods
     */

    @Override
    protected String getTestDataPath() {
        return "testData/org/elixir_lang/beam/decompiler";
    }

    /*
     * Private Instance Methods
     */

    private void assertDecompiled(String name) throws IOException {
        String testDataPath = getTestDataPath();
        String prefix = testDataPath + "/" + name + ".";

        File expectedFile = new File(prefix + "ex");
        String expected = Files.asCharSource(expectedFile, StandardCharsets.UTF_8).read();

        VfsRootAccess.allowRootAccess(getTestRootDisposable(), testDataPath);

        VirtualFile virtualFile = LocalFileSystem
                .getInstance()
                .findFileByIoFile(
                        new File(prefix + "beam")
                );

        assertNotNull(virtualFile);

        Decompiler decompiler = new Decompiler();
        String actual = decompiler.decompile(virtualFile).toString();

        assertParseable(name, virtualFile, actual);

        if (OVERWRITE_TESTDATA) {
            CommittedGolden.INSTANCE.assertMatches(expectedFile.getPath(), actual, REGENERATE);
        } else if (!expected.equals(actual)) {
            fail(buildCompactDiffMessage(name, expected, actual));
        }
    }

    private void assertParseable(String name, VirtualFile virtualFile, String decompiledText) {
        PsiFile compiledPsiFile = PsiManager.getInstance(getProject()).findFile(virtualFile);
        assertNotNull("Compiled PSI file is null for " + name, compiledPsiFile);
        assertTrue("PSI file is not compiled for " + name, compiledPsiFile instanceof PsiCompiledFile);

        PsiFile decompiledPsiFile = ((PsiCompiledFile) compiledPsiFile).getDecompiledPsiFile();
        assertNotNull("Decompiled PSI file is null for " + name, decompiledPsiFile);

        PsiErrorElement firstParseError = PsiTreeUtil.findChildOfType(decompiledPsiFile, PsiErrorElement.class);

        if (firstParseError != null) {
            int errorOffset = firstParseError.getTextRange().getStartOffset();
            String failingLine = parseErrorLine(decompiledText, errorOffset);
            String description = firstParseError.getErrorDescription();
            String context = parseErrorContext(decompiledText, errorOffset);

            fail(
                    "DECOMPILATION PARSE ERROR: " + name +
                            (description.isBlank() ? "" : "\nDescription: " + description) +
                    (failingLine == null || failingLine.isBlank() ? "" : "\nLine: " + failingLine) +
                    "\nError offset: " + errorOffset +
                    "\n\n--- Context (20 lines before/after error) ---\n" + context +
                    "\n--- End context ---"
            );
        }
    }

    private String parseErrorLine(String text, int offset) {
        if (text.isEmpty() || offset < 0 || offset > text.length()) {
            return null;
        }

        int lineStart = text.lastIndexOf('\n', Math.max(offset - 1, 0));
        lineStart = lineStart < 0 ? 0 : lineStart + 1;

        int lineEnd = text.indexOf('\n', offset);
        lineEnd = lineEnd < 0 ? text.length() : lineEnd;

        return text.substring(lineStart, lineEnd);
    }

    private String parseErrorContext(String text, int offset) {
        int surroundingLines = 20;
        if (text.isEmpty() || offset < 0 || offset > text.length()) {
            return "(no context available)";
        }

        String[] lines = text.split("\n", -1);
        // Find which line the offset is on
        int charCount = 0;
        int errorLine = 0;
        for (int i = 0; i < lines.length; i++) {
            int lineLen = lines[i].length() + 1; // +1 for the \n
            if (charCount + lineLen > offset) {
                errorLine = i;
                break;
            }
            charCount += lineLen;
        }

        int startLine = Math.max(0, errorLine - surroundingLines);
        int endLine = Math.min(lines.length - 1, errorLine + surroundingLines);

        StringBuilder sb = new StringBuilder();
        for (int i = startLine; i <= endLine; i++) {
            String marker = (i == errorLine) ? ">>> " : "    ";
            sb.append(String.format("%s%4d | %s%n", marker, i + 1, lines[i]));
        }
        return sb.toString();
    }

    private String buildCompactDiffMessage(String name, String expected, String actual) {
        int expectedLen = expected.length();
        int actualLen = actual.length();
        int minLen = Math.min(expectedLen, actualLen);

        // Find first difference
        int diffPos = 0;
        while (diffPos < minLen && expected.charAt(diffPos) == actual.charAt(diffPos)) {
            diffPos++;
        }

        // Calculate line and column
        int line = 1;
        int col = 1;
        int lineStart = 0;
        for (int i = 0; i < diffPos && i < expectedLen; i++) {
            if (expected.charAt(i) == '\n') {
                line++;
                col = 1;
                lineStart = i + 1;
            } else {
                col++;
            }
        }

        // Get the line containing the difference
        int lineEnd = expected.indexOf('\n', diffPos);
        if (lineEnd == -1) lineEnd = expectedLen;

        String expectedLine = expected.substring(lineStart, Math.min(lineEnd, expectedLen));

        lineEnd = actual.indexOf('\n', diffPos);
        if (lineEnd == -1) lineEnd = actualLen;
        String actualLine = actual.substring(lineStart, Math.min(lineEnd, actualLen));

        // Truncate lines if they're too long
        int maxLineLen = 120;
        String expectedLineDisplay = expectedLine.length() > maxLineLen
            ? expectedLine.substring(0, maxLineLen) + "..."
            : expectedLine;
        String actualLineDisplay = actualLine.length() > maxLineLen
            ? actualLine.substring(0, maxLineLen) + "..."
            : actualLine;

        // Get a few characters around the diff point for context
        int contextSize = 40;
        int contextStart = Math.max(0, diffPos - contextSize);
        int expectedEnd = Math.min(expectedLen, diffPos + contextSize);
        int actualEnd = Math.min(actualLen, diffPos + contextSize);

        String expectedSnippet = expected.substring(contextStart, expectedEnd);
        String actualSnippet = actual.substring(contextStart, actualEnd);

        // Show the exact character that differs
        char expectedChar = diffPos < expectedLen ? expected.charAt(diffPos) : '�';
        char actualChar = diffPos < actualLen ? actual.charAt(diffPos) : '�';

        return String.format(
            "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━%n" +
            "DECOMPILATION MISMATCH: %s%n" +
            "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━%n" +
            "Position: char %d, line %d, col %d%n" +
            "Length:   expected=%d, actual=%d (diff: %d)%n" +
            "%n" +
            "Character at difference:%n" +
            "  Expected: '%s' (0x%04x)%n" +
            "  Actual:   '%s' (0x%04x)%n" +
            "%n" +
            "Line %d context:%n" +
            "  Expected: %s%n" +
            "  Actual:   %s%n" +
            "%n" +
            "Snippet (40 chars before/after):%n" +
            "  Expected: %s%n" +
            "  Actual:   %s%n" +
            "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━",
            name,
            diffPos, line, col,
            expectedLen, actualLen, (expectedLen - actualLen),
            escapeChar(expectedChar), (int) expectedChar,
            escapeChar(actualChar), (int) actualChar,
            line,
            expectedLineDisplay,
            actualLineDisplay,
            escapeForDisplay(expectedSnippet),
            escapeForDisplay(actualSnippet)
        );
    }

    private String escapeChar(char c) {
        return switch (c) {
            case '\n' -> "\\n";
            case '\r' -> "\\r";
            case '\t' -> "\\t";
            case ' ' -> "SPACE";
            case '�' -> "EOF";
            default -> String.valueOf(c);
        };
    }

    private String escapeForDisplay(String s) {
        return s.replace("\n", "⏎\n")
                .replace("\r", "␍")
                .replace("\t", "→   ");
    }
}
