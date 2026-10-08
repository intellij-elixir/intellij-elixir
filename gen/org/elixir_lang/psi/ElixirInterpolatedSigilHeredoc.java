// This is a generated file. Not intended for manual editing.
package org.elixir_lang.psi;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElement;
import com.ericsson.otp.erlang.OtpErlangObject;
import com.intellij.psi.LiteralTextEscaper;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.util.concurrency.annotations.RequiresReadLock;

public interface ElixirInterpolatedSigilHeredoc extends Interpolated, SigilHeredocLiteral {

  @Nullable
  ElixirHeredocPrefix getHeredocPrefix();

  @NotNull
  List<ElixirInterpolatedHeredocLine> getInterpolatedHeredocLineList();

  @Nullable
  ElixirSigilModifiers getSigilModifiers();

  @NotNull LiteralTextEscaper<? extends PsiLanguageInjectionHost> createLiteralTextEscaper();

  @NotNull List<? extends HeredocLineable> getHeredocLineList();

  @NotNull Integer indentation();

  boolean isValidHost();

  @RequiresReadLock
  @NotNull OtpErlangObject quote();

  @NotNull String sigilDelimiter();

  @NotNull String sigilName();

  PsiLanguageInjectionHost updateText(@NotNull String text);

}
