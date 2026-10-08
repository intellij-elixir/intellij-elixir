// This is a generated file. Not intended for manual editing.
package org.elixir_lang.psi;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElement;
import com.ericsson.otp.erlang.OtpErlangObject;
import com.intellij.psi.LiteralTextEscaper;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.util.concurrency.annotations.RequiresReadLock;

public interface ElixirLine extends Atomable, Interpolated, Line, Quotable, Quote {

  @Nullable
  ElixirLineBody getLineBody();

  @NotNull LiteralTextEscaper<? extends PsiLanguageInjectionHost> createLiteralTextEscaper();

  @Nullable Body getBody();

  boolean isCharList();

  boolean isValidHost();

  @RequiresReadLock
  @NotNull OtpErlangObject quote();

  PsiLanguageInjectionHost updateText(@NotNull String text);

}
