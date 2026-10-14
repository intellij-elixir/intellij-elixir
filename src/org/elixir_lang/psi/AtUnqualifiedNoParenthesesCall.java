package org.elixir_lang.psi;

import com.intellij.psi.PsiElement;
import org.elixir_lang.psi.call.Call;
import org.elixir_lang.psi.call.StubBased;
import org.elixir_lang.psi.call.arguments.star.NoParenthesesOneArgument;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static org.elixir_lang.psi.impl.PsiElementImplKt.stripAccessExpressionAndParentheses;

/**
 * atPrefixOperator IDENTIFIER CALL bracketArguments
 */
public interface AtUnqualifiedNoParenthesesCall<Stub extends org.elixir_lang.psi.stub.call.Stub>
        extends Call, NoParenthesesOneArgument, Quotable, StubBased<Stub> {
    @Contract(pure = true)
    @NotNull
    ElixirAtIdentifier getAtIdentifier();

    /**
     * The expression the attribute is given, as Elixir quotes it: {@code @name x}, {@code @name (x)} and
     * {@code @name ((x))} all have argument {@code x}. {@code null} unless there is exactly one.
     */
    @Contract(pure = true)
    @Nullable
    default PsiElement getArgument() {
        PsiElement[] arguments = getNoParenthesesOneArgument().arguments();

        return arguments.length == 1 ? stripAccessExpressionAndParentheses(arguments[0]) : null;
    }
}
