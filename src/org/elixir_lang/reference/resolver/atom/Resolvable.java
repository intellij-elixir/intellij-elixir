package org.elixir_lang.reference.resolver.atom;

import com.intellij.lang.ASTNode;
import com.intellij.psi.ResolveResult;
import com.intellij.psi.tree.IElementType;
import com.intellij.util.concurrency.annotations.RequiresReadLock;
import org.elixir_lang.psi.*;
import org.elixir_lang.psi.impl.ElixirAtomImplKt;
import org.elixir_lang.reference.resolver.atom.resolvable.Exact;
import org.elixir_lang.reference.resolver.atom.resolvable.Pattern;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedList;
import java.util.List;

import static org.elixir_lang.psi.impl.ParentImpl.addChildTextCodePoints;

/**
 * How to resolve an {@link ElixirAtom}.
 * <p>
 * If the {ElixirAtom} is a normal, unquoted atom, it can be resolved exactly, but if it's quoted and contains
 * interpolation, then it cannot be resolved exactly.
 */
public abstract class Resolvable {
    /** An unquoted atom has no value only when it is longer than an atom can be, so it names no module. */
    private static final Resolvable NOTHING = new Resolvable() {
        @Override
        public ResolveResult[] resolve(@NotNull ElixirAtom element) {
            return ResolveResult.EMPTY_ARRAY;
        }
    };

    @NotNull
    @RequiresReadLock
    public static Resolvable resolvable(@NotNull ElixirAtom atom) {
        String indexName = ElixirAtomImplKt.indexName(atom);
        ElixirLine line = atom.getLine();
        Resolvable resolvable;

        if (indexName != null) {
            resolvable = new Exact(indexName);
        } else if (line != null) {
            resolvable = resolvable(line);
        } else {
            resolvable = NOTHING;
        }

        return resolvable;
    }

    @NotNull
    private static <I extends Bodied & Parent> Resolvable resolvable(@NotNull I parentBodied) {
        Body body = parentBodied.getBody();

        return resolvable(parentBodied, body.getNode().getChildren(null));
    }

    @NotNull
    private static Resolvable resolvable(@NotNull Parent parent, @NotNull ASTNode[] children) {
        List<String> regexList = new LinkedList<>();
        List<Integer> codePointList = null;

        for (ASTNode child : children) {
            IElementType elementType = child.getElementType();

            if (elementType == ElixirTypes.FRAGMENT) {
                codePointList = parent.addFragmentCodePoints(codePointList, child);
            } else if (elementType == ElixirTypes.ESCAPED_CHARACTER) {
                codePointList = parent.addEscapedCharacterCodePoints(codePointList, child);
            } else if (elementType == ElixirTypes.ESCAPED_EOL) {
                codePointList = parent.addEscapedEOL(codePointList, child);
            } else if (elementType == ElixirTypes.HEXADECIMAL_ESCAPE_PREFIX) {
                codePointList = addChildTextCodePoints(codePointList, child);
            } else if (elementType == ElixirTypes.INTERPOLATION) {
                if (codePointList != null) {
                    regexList.add(codePointListToRegex(codePointList));
                    codePointList = null;
                }

                regexList.add(interpolation());
            } else if (elementType == ElixirTypes.QUOTE_HEXADECIMAL_ESCAPE_SEQUENCE ||
                    elementType == ElixirTypes.SIGIL_HEXADECIMAL_ESCAPE_SEQUENCE) {
                codePointList = parent.addHexadecimalEscapeSequenceCodePoints(codePointList, child);
            } else {
                throw new UnsupportedOperationException("Can't convert to Resolvable " + child);
            }
        }

        if (codePointList != null) {
            regexList.add(codePointListToRegex(codePointList));
        }

        return new Pattern(join(regexList));
    }

    @NotNull
    private static String join(List<String> regexList) {
        return String.join("", regexList);
    }

    @Contract(pure = true)
    @NotNull
    private static String interpolation() {
        return ".*";
    }

    @NotNull
    private static String codePointListToRegex(@NotNull List<Integer> codePointList) {
        String string = codePointListToString(codePointList);
        return java.util.regex.Pattern.quote(string);
    }

    @NotNull
    private static String codePointListToString(@NotNull List<Integer> codePointList) {
        StringBuilder stringAccumulator = new StringBuilder();

        for (int codePoint : codePointList) {
            stringAccumulator.appendCodePoint(codePoint);
        }

        return stringAccumulator.toString();
    }

    public abstract ResolveResult[] resolve(@NotNull ElixirAtom element);
}
