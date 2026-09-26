package org.elixir_lang.heex.lexer;

import com.intellij.lexer.Lexer;
import com.intellij.lexer.LexerBase;
import com.intellij.lexer.LexerPosition;
import com.intellij.openapi.project.Project;
import com.intellij.psi.tree.IElementType;
import org.elixir_lang.ElixirLanguage;
import org.elixir_lang.ElixirLexer;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

import static com.intellij.psi.TokenType.BAD_CHARACTER;
import static com.intellij.psi.TokenType.WHITE_SPACE;
import static org.elixir_lang.heex.psi.Types.*;
import static org.elixir_lang.heex.psi.Types.COMMENT;
import static org.elixir_lang.psi.ElixirTypes.*;

/**
 * Like {@link com.intellij.lexer.LookAheadLexer}, but uses 2 base lexers.  Since which base lexer is being used, we
 * can't use LookAheadLexer since it's {@link com.intellij.lexer.LookAheadLexer.LookAheadLexerPosition} only works for a
 * single lexer.
 */
public class HTMLEmbeddedElixir extends LexerBase {
    @NotNull
    private static final Map<IElementType, IElementType> HEEX_TOKEN_TYPE_TO_ELIXIR_TOKEN_TYPE = new HashMap<>();
    @NotNull
    final Lexer heexLexer;
    @NotNull
    final Lexer elixirLexer;

    static {
        HEEX_TOKEN_TYPE_TO_ELIXIR_TOKEN_TYPE.put(BAD_CHARACTER, BAD_CHARACTER);
        HEEX_TOKEN_TYPE_TO_ELIXIR_TOKEN_TYPE.put(BRACE_CLOSING, EEX_CLOSING);
        HEEX_TOKEN_TYPE_TO_ELIXIR_TOKEN_TYPE.put(BRACE_OPENING, EEX_OPENING);
        HEEX_TOKEN_TYPE_TO_ELIXIR_TOKEN_TYPE.put(CLOSING, EEX_CLOSING);
        HEEX_TOKEN_TYPE_TO_ELIXIR_TOKEN_TYPE.put(COMMENT, EEX_COMMENT);
        HEEX_TOKEN_TYPE_TO_ELIXIR_TOKEN_TYPE.put(COMMENT_MARKER, EEX_COMMENT_MARKER);
        HEEX_TOKEN_TYPE_TO_ELIXIR_TOKEN_TYPE.put(DATA, EEX_DATA);
        HEEX_TOKEN_TYPE_TO_ELIXIR_TOKEN_TYPE.put(ESCAPED_OPENING, EEX_ESCAPED_OPENING);
        HEEX_TOKEN_TYPE_TO_ELIXIR_TOKEN_TYPE.put(EMPTY_MARKER, EEX_EMPTY_MARKER);
        HEEX_TOKEN_TYPE_TO_ELIXIR_TOKEN_TYPE.put(EQUALS_MARKER, EEX_EQUALS_MARKER);
        HEEX_TOKEN_TYPE_TO_ELIXIR_TOKEN_TYPE.put(FORWARD_SLASH_MARKER, EEX_FORWARD_SLASH_MARKER);
        HEEX_TOKEN_TYPE_TO_ELIXIR_TOKEN_TYPE.put(OPENING, EEX_OPENING);
        HEEX_TOKEN_TYPE_TO_ELIXIR_TOKEN_TYPE.put(PIPE_MARKER, EEX_PIPE_MARKER);
        HEEX_TOKEN_TYPE_TO_ELIXIR_TOKEN_TYPE.put(WHITE_SPACE, WHITE_SPACE);
    }

    public HTMLEmbeddedElixir(Project project) {
        this.heexLexer = new LookAhead();
        this.elixirLexer = new ElixirLexer(project);
    }

    @Contract(pure = true)
    @Nullable
    private static <T> T forLanguage(@NotNull Lexer heexLexer, @NotNull T forHEEx, @Nullable T forElixir) {
       return forLanguage(heexLexer.getTokenType(), forHEEx, forElixir);
    }

    @Contract(pure = true)
    @Nullable
    private static <T> T forLanguage(@Nullable IElementType tokenType, @NotNull T forHEEx, @Nullable T forElixir) {
        T forLanguage;

        if (tokenType == ELIXIR) {
            forLanguage = forElixir;
        } else {
            forLanguage = forHEEx;
        }

        return forLanguage;
    }

    @Contract(pure = true)
    @NotNull
    private Lexer lexer() {
        //noinspection ConstantConditions
        return forLanguage(heexLexer, heexLexer, elixirLexer);
    }

    public void advance() {
        if (heexLexer.getTokenType() == ELIXIR) {
            elixirLexer.advance();

            if (elixirLexer.getTokenType() == null) {
                heexLexer.advance();
            }
        } else {
            heexLexer.advance();

            if (heexLexer.getTokenType() == ELIXIR) {
                // start automatically does equivalent of `advance` since `elixirLexer` is also a look-ahead lexer
                elixirLexer.start(getBufferSequence(), heexLexer.getTokenStart(), heexLexer.getTokenEnd());
            }
        }
    }

    @NotNull
    public CharSequence getBufferSequence() {
        // elixirLexer only has a subsequence that is `heexLexer`'s
        return heexLexer.getBufferSequence();
    }

    public int getBufferEnd() {
        // since {@link #getBufferSequence} uses `heexLexer`, so does this.
        return heexLexer.getBufferEnd();
    }

    private int lexerLanguageFlag() {
        //noinspection ConstantConditions
        return forLanguage(heexLexer, 0, 1);
    }

    public int getState() {
        return lexer().getState() | (lexerLanguageFlag() << 16);
    }

    public int getTokenEnd() {
        return lexer().getTokenEnd();
    }

    public int getTokenStart() {
        return lexer().getTokenStart();
    }

    @NotNull
    public HTMLEmbeddedElixir.Position getCurrentPosition() {
        return new Position(this);
    }

    public final void restore(@NotNull final LexerPosition position) {
        restore((Position) position);
    }

    private void restore(Position position) {
        restoreHEExPosition(position.heexPosition);
        restoreElixirPosition(position.elixirPosition);
    }

    private void restoreHEExPosition(@NotNull LexerPosition heexPosition) {
        heexLexer.restore(heexPosition);
    }

    private void restoreElixirPosition(@Nullable LexerPosition elixirPosition) {
        if (elixirPosition != null) {
            elixirLexer.start(getBufferSequence(), heexLexer.getTokenStart(), heexLexer.getTokenEnd());
            elixirLexer.restore(elixirPosition);
        }
    }

    @Nullable
    public IElementType getTokenType() {
        IElementType tokenType = lexer().getTokenType();

        if (tokenType != null && tokenType.getLanguage() != ElixirLanguage.INSTANCE) {
            IElementType elixirTokenType = HEEX_TOKEN_TYPE_TO_ELIXIR_TOKEN_TYPE.get(tokenType);

            assert elixirTokenType != null : "HEEx TokenType " + tokenType + " is not mapped to an Elixir TokenType";

            tokenType = elixirTokenType;
        }

        return tokenType;
    }

    @Override
    public void start(@NotNull CharSequence buffer, int startOffset, int endOffset, int initialState) {
        heexLexer.start(buffer, startOffset, endOffset, initialState & 0xFFFF);

        if (heexLexer.getTokenType() == ELIXIR) {
            elixirLexer.start(buffer, startOffset, endOffset);
        } else {
            elixirLexer.start(buffer, startOffset, endOffset);
        }
    }

    protected static class Position implements LexerPosition {
        @NotNull
        private final LexerPosition heexPosition;
        @Nullable
        private final LexerPosition elixirPosition;

        Position(final HTMLEmbeddedElixir lexer) {
            this.heexPosition = lexer.heexLexer.getCurrentPosition();

            if (lexer.heexLexer.getTokenType() == ELIXIR) {
                this.elixirPosition = lexer.elixirLexer.getCurrentPosition();
            } else {
                this.elixirPosition = null;
            }
        }

        @Contract(pure = true)
        @NotNull
        private LexerPosition position() {
            LexerPosition position;

            if (elixirPosition != null) {
                position = elixirPosition;
            } else {
                position = heexPosition;
            }

            return position;
        }

        public int getOffset() {
            return position().getOffset();
        }

        public int getState() {
            return position().getState();
        }
    }
}
