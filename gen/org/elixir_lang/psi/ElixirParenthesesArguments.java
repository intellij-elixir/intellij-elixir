// This is a generated file. Not intended for manual editing.
package org.elixir_lang.psi;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElement;

public interface ElixirParenthesesArguments extends Arguments, QuotableArguments {

  @NotNull
  List<ElixirEmptyParentheses> getEmptyParenthesesList();

  @Nullable
  ElixirKeywords getKeywords();

  @NotNull
  List<ElixirUnmatchedExpression> getUnmatchedExpressionList();

  @Nullable
  ElixirUnqualifiedNoParenthesesManyArgumentsCall getUnqualifiedNoParenthesesManyArgumentsCall();

  @NotNull PsiElement[] arguments();

}
