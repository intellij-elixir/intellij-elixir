package org.elixir_lang.parser;

import com.intellij.lang.ASTNode;
import com.intellij.lang.ITokenTypeRemapper;
import com.intellij.lang.LightPsiParser;
import com.intellij.lang.PsiBuilder;
import com.intellij.lang.PsiParser;
import com.intellij.lang.impl.PsiBuilderImpl;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.impl.source.resolve.FileContextUtil;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Parses without recording the tokens each rule expected, which only error messages and error recovery read, and
 * parses again recording them when the first pass meets an error.
 * <p>
 * A file whose last parse had an error is probably still being edited, so its next parse starts with the recording
 * pass rather than paying for both. A copy of the file (a reparse, a completion copy) writes the memory as the file;
 * a wrong guess costs one pass.
 */
public final class TwoPassParser implements PsiParser, LightPsiParser {
    private static final Key<Boolean> LAST_PARSE_HAD_ERRORS = Key.create("ELIXIR_LAST_PARSE_HAD_ERRORS");

    private final ElixirParser parser = new ElixirParser();

    /** Whether the file's last parse reported an error. */
    public static boolean lastParseHadErrors(@NotNull VirtualFile file) {
        return file.getUserData(LAST_PARSE_HAD_ERRORS) == Boolean.TRUE;
    }

    @Override
    public @NotNull ASTNode parse(@NotNull IElementType root, @NotNull PsiBuilder builder) {
        parseLight(root, builder);

        return builder.getTreeBuilt();
    }

    @Override
    public void parseLight(@NotNull IElementType root, @NotNull PsiBuilder builder) {
        ITokenTypeRemapper remapper = ElixirParserUtil.remapper(builder);
        builder.setTokenTypeRemapper(remapper);

        // only PsiBuilderImpl can say whether a pass reported an error
        if (!(builder instanceof PsiBuilderImpl impl)) {
            parser.parseLight(root, builder);

            return;
        }

        VirtualFile file = fileOf(builder);
        boolean hadErrors = file != null && lastParseHadErrors(file);

        if (!hadErrors) {
            builder.putUserData(ElixirParserUtil.FAST_PASS, true);
            parser.parseLight(root, builder);
            hadErrors = impl.hasErrorsAfter(rootMarker(impl));

            if (hadErrors) {
                rootMarker(impl).rollbackTo();
                // a rollback leaves the builder believing it has skipped the leading whitespace; this resets that
                builder.setTokenTypeRemapper(remapper);
                builder.putUserData(ElixirParserUtil.FAST_PASS, false);
            }
        }

        if (hadErrors) {
            parser.parseLight(root, builder);
            hadErrors = impl.hasErrorsAfter(rootMarker(impl));
        }

        if (file != null) {
            file.putUserData(LAST_PARSE_HAD_ERRORS, hadErrors ? Boolean.TRUE : null);
        }
    }

    /**
     * The marker the generated parser opened first and closed last: the root. No marker may precede it, because the
     * builder places only its first marker before the leading whitespace and comments.
     */
    private static @NotNull PsiBuilder.Marker rootMarker(@NotNull PsiBuilderImpl builder) {
        return (PsiBuilder.Marker) builder.getProductions().getFirst();
    }

    /** The file being parsed; a reparse parses a copy, whose original is the file. */
    private static @Nullable VirtualFile fileOf(@NotNull PsiBuilder builder) {
        PsiFile file = builder.getUserData(FileContextUtil.CONTAINING_FILE_KEY);

        return file == null ? null : file.getOriginalFile().getViewProvider().getVirtualFile();
    }
}
