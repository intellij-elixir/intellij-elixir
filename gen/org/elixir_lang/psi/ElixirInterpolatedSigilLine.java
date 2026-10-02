// This is a generated file. Not intended for manual editing.
package org.elixir_lang.psi;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElement;
import com.ericsson.otp.erlang.OtpErlangObject;
import com.intellij.lang.ASTNode;
import com.intellij.psi.LiteralTextEscaper;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.util.concurrency.annotations.RequiresReadLock;

public interface ElixirInterpolatedSigilLine extends Interpolated, SigilLine {

  @Nullable
  ElixirInterpolatedSigilLineBody getInterpolatedSigilLineBody();

  @Nullable
  ElixirSigilModifiers getSigilModifiers();

  @NotNull List<Integer> addEscapedCharacterCodePoints(@Nullable List<Integer> codePointList, @NotNull ASTNode child);

  @RequiresReadLock
  @NotNull List<Integer> addEscapedEOL(@Nullable List<Integer> maybeCodePointList, @NotNull ASTNode child);

  @RequiresReadLock
  @NotNull List<Integer> addEscapedTerminator(@Nullable List<Integer> maybeCodePointList, @NotNull ASTNode child);

  @NotNull List<Integer> addFragmentCodePoints(@Nullable List<Integer> codePointList, @NotNull ASTNode child);

  @NotNull List<Integer> addHexadecimalEscapeSequenceCodePoints(@Nullable List<Integer> codePointList, @NotNull ASTNode child);

  @NotNull LiteralTextEscaper<? extends PsiLanguageInjectionHost> createLiteralTextEscaper();

  Body getBody();

  @Nullable Integer indentation();

  boolean isValidHost();

  @RequiresReadLock
  @NotNull OtpErlangObject quote();

  @NotNull String sigilDelimiter();

  @NotNull String sigilName();

  char terminator();

  PsiLanguageInjectionHost updateText(@NotNull String text);

}
