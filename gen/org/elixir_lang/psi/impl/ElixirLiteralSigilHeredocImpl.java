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

public class ElixirLiteralSigilHeredocImpl extends ASTWrapperPsiElement implements ElixirLiteralSigilHeredoc {

  public ElixirLiteralSigilHeredocImpl(@NotNull ASTNode node) {
    super(node);
  }

  public void accept(@NotNull ElixirVisitor visitor) {
    visitor.visitLiteralSigilHeredoc(this);
  }

  @Override
  public void accept(@NotNull PsiElementVisitor visitor) {
    if (visitor instanceof ElixirVisitor) accept((ElixirVisitor)visitor);
    else super.accept(visitor);
  }

  @Override
  @Nullable
  public ElixirHeredocPrefix getHeredocPrefix() {
    return PsiTreeUtil.getChildOfType(this, ElixirHeredocPrefix.class);
  }

  @Override
  @NotNull
  public List<ElixirLiteralHeredocLine> getLiteralHeredocLineList() {
    return PsiTreeUtil.getChildrenOfTypeAsList(this, ElixirLiteralHeredocLine.class);
  }

  @Override
  @Nullable
  public ElixirSigilModifiers getSigilModifiers() {
    return PsiTreeUtil.getChildOfType(this, ElixirSigilModifiers.class);
  }

  @Override
  public @NotNull LiteralTextEscaper<? extends PsiLanguageInjectionHost> createLiteralTextEscaper() {
    return ElixirPsiImplUtil.createLiteralTextEscaper(this);
  }

  @Override
  public @NotNull List<? extends HeredocLineable> getHeredocLineList() {
    return ElixirPsiImplUtil.getHeredocLineList(this);
  }

  @Override
  public @NotNull Integer indentation() {
    return ElixirPsiImplUtil.indentation(this);
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
  public @NotNull String sigilDelimiter() {
    return ElixirPsiImplUtil.sigilDelimiter(this);
  }

  @Override
  public @NotNull String sigilName() {
    return ElixirPsiImplUtil.sigilName(this);
  }

  @Override
  public PsiLanguageInjectionHost updateText(@NotNull String text) {
    return ElixirPsiImplUtil.updateText(this, text);
  }

}
