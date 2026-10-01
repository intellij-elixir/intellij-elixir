// This is a generated file. Not intended for manual editing.
package org.elixir_lang.psi;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElement;

public interface ElixirNoParenthesesOneArgument extends Arguments, MaybeModuleName, QuotableArguments {

  @NotNull
  List<ElixirEmptyParentheses> getEmptyParenthesesList();

  @NotNull
  List<ElixirMatchedExpression> getMatchedExpressionList();

  @Nullable
  ElixirNoParenthesesKeywords getNoParenthesesKeywords();

  @NotNull
  List<ElixirNoParenthesesManyStrictNoParenthesesExpression> getNoParenthesesManyStrictNoParenthesesExpressionList();

  @Nullable
  ElixirNoParenthesesStrict getNoParenthesesStrict();

  @Nullable
  ElixirUnqualifiedNoParenthesesManyArgumentsCall getUnqualifiedNoParenthesesManyArgumentsCall();

  @NotNull PsiElement[] arguments();

  boolean isModuleName();

}
