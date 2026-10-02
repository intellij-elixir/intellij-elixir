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

public class ElixirMapConstructionArgumentsImpl extends ASTWrapperPsiElement implements ElixirMapConstructionArguments {

  public ElixirMapConstructionArgumentsImpl(@NotNull ASTNode node) {
    super(node);
  }

  public void accept(@NotNull ElixirVisitor visitor) {
    visitor.visitMapConstructionArguments(this);
  }

  @Override
  public void accept(@NotNull PsiElementVisitor visitor) {
    if (visitor instanceof ElixirVisitor) accept((ElixirVisitor)visitor);
    else super.accept(visitor);
  }

  @Override
  @Nullable
  public ElixirAssociations getAssociations() {
    return PsiTreeUtil.getChildOfType(this, ElixirAssociations.class);
  }

  @Override
  @Nullable
  public ElixirAssociationsBase getAssociationsBase() {
    return PsiTreeUtil.getChildOfType(this, ElixirAssociationsBase.class);
  }

  @Override
  @Nullable
  public ElixirKeywords getKeywords() {
    return PsiTreeUtil.getChildOfType(this, ElixirKeywords.class);
  }

  @Override
  public @NotNull PsiElement[] arguments() {
    return ElixirPsiImplUtil.arguments(this);
  }

}
