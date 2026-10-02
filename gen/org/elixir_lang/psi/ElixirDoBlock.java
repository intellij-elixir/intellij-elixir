// This is a generated file. Not intended for manual editing.
package org.elixir_lang.psi;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElement;

public interface ElixirDoBlock extends QuotableArguments {

  @Nullable
  ElixirBlockList getBlockList();

  @NotNull
  List<ElixirEndOfExpression> getEndOfExpressionList();

  @Nullable
  ElixirStab getStab();

}
