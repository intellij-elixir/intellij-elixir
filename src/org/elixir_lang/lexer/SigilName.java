package org.elixir_lang.lexer;

import com.intellij.psi.tree.IElementType;
import org.elixir_lang.psi.ElixirTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Created by kadie.enheduanna.inanna on 8/20/14.
 */
public class SigilName {
    public static IElementType elementType(String sigilName) {
        IElementType elementType;

        if (isInterpolating(sigilName)) {
            elementType = ElixirTypes.INTERPOLATING_SIGIL_NAME;
        } else {
            elementType = ElixirTypes.LITERAL_SIGIL_NAME;
        }

        return elementType;
    }

    public static boolean is(char character) {
        return (character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z');
    }

    public static boolean isInterpolating(String sigilName) {
        char first = sigilName.charAt(0);

        return (first >= 'a' && first <= 'z');
    }

    public static final int FUNCTION_ARITY = 2;
    private static final String FUNCTION_PREFIX = "sigil_";
    private static final Pattern NAME = Pattern.compile("[a-z]|[A-Z][A-Z0-9]*");

    /**
     * The sigil a function named {@code functionName} is at {@code arity}, as the lexer's {@code SIGIL_NAME} and
     * Elixir's {@code import only: :sigils} read it, or {@code null} when it is none.
     */
    @Nullable
    public static String ofFunction(@NotNull String functionName, int arity) {
        if (arity != FUNCTION_ARITY ||!functionName.startsWith(FUNCTION_PREFIX)) {
            return null;
        }

        String sigilName = functionName.substring(FUNCTION_PREFIX.length());

        return NAME.matcher(sigilName).matches() ? sigilName : null;
    }
}
