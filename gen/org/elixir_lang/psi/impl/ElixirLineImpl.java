// This is a generated file. Not intended for manual editing.
package org.elixir_lang.psi.impl;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.util.PsiTreeUtil;
import static org.elixir_lang.psi.ElixirTypes.*;
import com.intellij.extapi.psi.ASTWrapperPsiElement;
import org.elixir_lang.psi.*;
import com.ericsson.otp.erlang.OtpErlangObject;
import com.intellij.psi.LiteralTextEscaper;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.util.concurrency.annotations.RequiresReadLock;

public class ElixirLineImpl extends ASTWrapperPsiElement implements ElixirLine {

  public ElixirLineImpl(@NotNull ASTNode node) {
    super(node);
  }

  public void accept(@NotNull ElixirVisitor visitor) {
    visitor.visitLine(this);
  }

  @Override
  public void accept(@NotNull PsiElementVisitor visitor) {
    if (visitor instanceof ElixirVisitor) accept((ElixirVisitor)visitor);
    else super.accept(visitor);
  }

  @Override
  @Nullable
  public ElixirLineBody getLineBody() {
    return PsiTreeUtil.getChildOfType(this, ElixirLineBody.class);
  }

  @Override
  public @NotNull List<Integer> addEscapedCharacterCodePoints(@Nullable List<Integer> codePointList, @NotNull ASTNode child) {
    return ElixirPsiImplUtil.addEscapedCharacterCodePoints(this, codePointList, child);
  }

  @Override
  @RequiresReadLock
  public @NotNull List<Integer> addEscapedEOL(@Nullable List<Integer> maybeCodePointList, @NotNull ASTNode child) {
    return ElixirPsiImplUtil.addEscapedEOL(this, maybeCodePointList, child);
  }

  @Override
  @RequiresReadLock
  public @NotNull List<Integer> addEscapedTerminator(@Nullable List<Integer> maybeCodePointList, @NotNull ASTNode child) {
    return ElixirPsiImplUtil.addEscapedTerminator(this, maybeCodePointList, child);
  }

  @Override
  public @NotNull List<Integer> addFragmentCodePoints(@Nullable List<Integer> codePointList, @NotNull ASTNode child) {
    return ElixirPsiImplUtil.addFragmentCodePoints(this, codePointList, child);
  }

  @Override
  public @NotNull List<Integer> addHexadecimalEscapeSequenceCodePoints(@Nullable List<Integer> codePointList, @NotNull ASTNode child) {
    return ElixirPsiImplUtil.addHexadecimalEscapeSequenceCodePoints(this, codePointList, child);
  }

  @Override
  public @NotNull LiteralTextEscaper<? extends PsiLanguageInjectionHost> createLiteralTextEscaper() {
    return ElixirPsiImplUtil.createLiteralTextEscaper(this);
  }

  @Override
  public @Nullable Body getBody() {
    return ElixirPsiImplUtil.getBody(this);
  }

  @Override
  public boolean isCharList() {
    return ElixirPsiImplUtil.isCharList(this);
  }

  @Override
  public boolean isValidHost() {
    return ElixirPsiImplUtil.isValidHost(this);
  }

  @Override
  @RequiresReadLock
  public @NotNull OtpErlangObject quote() {
    return ElixirPsiImplUtil.quote(this);
  }

  @Override
  public PsiLanguageInjectionHost updateText(@NotNull String text) {
    return ElixirPsiImplUtil.updateText(this, text);
  }

}
