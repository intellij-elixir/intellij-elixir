// This is a generated file. Not intended for manual editing.
package org.elixir_lang.psi;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElement;
import com.ericsson.otp.erlang.OtpErlangObject;
import com.intellij.psi.PsiReference;
import com.intellij.util.concurrency.annotations.RequiresReadLock;

public interface ElixirMatchedAtOperation extends ElixirMatchedExpression, AtOperation {

  @NotNull
  ElixirAtPrefixOperator getAtPrefixOperator();

  @Nullable
  ElixirMatchedExpression getMatchedExpression();

  @Nullable PsiReference getReference();

  @RequiresReadLock
  @NotNull String moduleAttributeName();

  @Nullable Quotable operand();

  @NotNull Operator operator();

  @RequiresReadLock
  @NotNull OtpErlangObject quote();

}
