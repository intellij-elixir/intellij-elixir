package org.elixir_lang.eex.lexer;

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
import java.util.Objects;

import static com.intellij.psi.TokenType.BAD_CHARACTER;
import static com.intellij.psi.TokenType.WHITE_SPACE;
import static org.elixir_lang.eex.psi.Types.*;
import static org.elixir_lang.eex.psi.Types.COMMENT;
import static org.elixir_lang.psi.ElixirTypes.*;

/**
 * Like {@link com.intellij.lexer.LookAheadLexer}, but uses 2 base lexers.  Since which base lexer is being used, we
 * can't use LookAheadLexer since it's {@code LookAheadLexer.LookAheadLexerPosition} only works for a
 * single lexer.
 */
public class EmbeddedElixir extends LexerBase {
    @NotNull
    private static final Map<IElementType, IElementType> EEX_TOKEN_TYPE_TO_ELIXIR_TOKEN_TYPE = new HashMap<>();
    @NotNull
    final Lexer eexLexer;
    @NotNull
    final Lexer elixirLexer;

    static {
        EEX_TOKEN_TYPE_TO_ELIXIR_TOKEN_TYPE.put(BAD_CHARACTER, BAD_CHARACTER);
        EEX_TOKEN_TYPE_TO_ELIXIR_TOKEN_TYPE.put(CLOSING, EEX_CLOSING);
        EEX_TOKEN_TYPE_TO_ELIXIR_TOKEN_TYPE.put(COMMENT, EEX_COMMENT);
        EEX_TOKEN_TYPE_TO_ELIXIR_TOKEN_TYPE.put(COMMENT_MARKER, EEX_COMMENT_MARKER);
        EEX_TOKEN_TYPE_TO_ELIXIR_TOKEN_TYPE.put(DATA, EEX_DATA);
        EEX_TOKEN_TYPE_TO_ELIXIR_TOKEN_TYPE.put(ESCAPED_OPENING, EEX_ESCAPED_OPENING);
        EEX_TOKEN_TYPE_TO_ELIXIR_TOKEN_TYPE.put(EMPTY_MARKER, EEX_EMPTY_MARKER);
        EEX_TOKEN_TYPE_TO_ELIXIR_TOKEN_TYPE.put(EQUALS_MARKER, EEX_EQUALS_MARKER);
        EEX_TOKEN_TYPE_TO_ELIXIR_TOKEN_TYPE.put(FORWARD_SLASH_MARKER, EEX_FORWARD_SLASH_MARKER);
        EEX_TOKEN_TYPE_TO_ELIXIR_TOKEN_TYPE.put(OPENING, EEX_OPENING);
        EEX_TOKEN_TYPE_TO_ELIXIR_TOKEN_TYPE.put(PIPE_MARKER, EEX_PIPE_MARKER);
        EEX_TOKEN_TYPE_TO_ELIXIR_TOKEN_TYPE.put(WHITE_SPACE, WHITE_SPACE);
    }

    public EmbeddedElixir(Project project) {
        this.eexLexer = new LookAhead();
        this.elixirLexer = new ElixirLexer(project);
    }

    @Contract(pure = true)
    @Nullable
    private static <T> T forLanguage(@NotNull Lexer eexLexer, @NotNull T forEEx, @Nullable T forElixir) {
       return forLanguage(eexLexer.getTokenType(), forEEx, forElixir);
    }

    @Contract(pure = true)
    @Nullable
    private static <T> T forLanguage(@Nullable IElementType tokenType, @NotNull T forEEx, @Nullable T forElixir) {
        T forLanguage;

        if (tokenType == ELIXIR) {
            forLanguage = forElixir;
        } else {
            forLanguage = forEEx;
        }

        return forLanguage;
    }

    @Contract(pure = true)
    @NotNull
    private Lexer lexer() {
        //noinspection ConstantConditions
        return forLanguage(eexLexer, eexLexer, elixirLexer);
    }

    public void advance() {
        if (eexLexer.getTokenType() == ELIXIR) {
            elixirLexer.advance();

            if (elixirLexer.getTokenType() == null) {
                eexLexer.advance();
            }
        } else {
            eexLexer.advance();

            if (eexLexer.getTokenType() == ELIXIR) {
                // start automatically does equivalent of `advance` since `elixirLexer` is also a look-ahead lexer
                elixirLexer.start(getBufferSequence(), eexLexer.getTokenStart(), eexLexer.getTokenEnd());
            }
        }
    }

    @NotNull
    public CharSequence getBufferSequence() {
        // elixirLexer only has a subsequence that is `eexLexer`'s
        return eexLexer.getBufferSequence();
    }

    public int getBufferEnd() {
        // since {@link #getBufferSequence} uses `eexLexer`, so does this.
        return eexLexer.getBufferEnd();
    }

    private int lexerLanguageFlag() {
        //noinspection ConstantConditions
        return forLanguage(eexLexer, 0, 1);
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
    public LexerPosition getCurrentPosition() {
        return new EmbeddedElixir.Position(this);
    }

    public final void restore(@NotNull final LexerPosition position) {
        restore((EmbeddedElixir.Position) position);
    }

    private void restore(EmbeddedElixir.Position position) {
        restoreEExPosition(position.eexPosition);
        restoreElixirPosition(position.elixirPosition);
    }

    private void restoreEExPosition(@NotNull LexerPosition eexPosition) {
        eexLexer.restore(eexPosition);
    }

    private void restoreElixirPosition(@Nullable LexerPosition elixirPosition) {
        if (elixirPosition != null) {
            elixirLexer.start(getBufferSequence(), eexLexer.getTokenStart(), eexLexer.getTokenEnd());
            elixirLexer.restore(elixirPosition);
        }
    }

    @Nullable
    public IElementType getTokenType() {
        IElementType tokenType = lexer().getTokenType();

        if (tokenType != null && tokenType.getLanguage() != ElixirLanguage.INSTANCE) {
            IElementType elixirTokenType = EEX_TOKEN_TYPE_TO_ELIXIR_TOKEN_TYPE.get(tokenType);

            assert elixirTokenType != null : "EEx TokenType " + tokenType + " is not mapped to an Elixir TokenType";

            tokenType = elixirTokenType;
        }

        return tokenType;
    }

    @Override
    public void start(@NotNull CharSequence buffer, int startOffset, int endOffset, int initialState) {
        eexLexer.start(buffer, startOffset, endOffset, initialState & 0xFFFF);

        elixirLexer.start(buffer, startOffset, endOffset);
    }

    protected static class Position implements LexerPosition {
        @NotNull
        private final LexerPosition eexPosition;
        @Nullable
        private final LexerPosition elixirPosition;

        Position(final EmbeddedElixir lexer) {
            this.eexPosition = lexer.eexLexer.getCurrentPosition();

            if (lexer.eexLexer.getTokenType() == ELIXIR) {
                this.elixirPosition = lexer.elixirLexer.getCurrentPosition();
            } else {
                this.elixirPosition = null;
            }
        }

        @Contract(pure = true)
        @NotNull
        private LexerPosition position() {
            return Objects.requireNonNullElse(elixirPosition, eexPosition);
        }

        public int getOffset() {
            return position().getOffset();
        }

        public int getState() {
            return position().getState();
        }
    }
}
