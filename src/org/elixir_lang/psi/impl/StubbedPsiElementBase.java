package org.elixir_lang.psi.impl;

import com.intellij.extapi.psi.StubBasedPsiElementBase;
import com.intellij.lang.ASTNode;
import com.intellij.psi.stubs.IStubElementType;
import com.intellij.psi.stubs.StubElement;
import org.jetbrains.annotations.NotNull;

public class StubbedPsiElementBase<T extends StubElement<?>> extends StubBasedPsiElementBase<T> {
  /*
   * Constructors
   */

  public StubbedPsiElementBase(@NotNull T stub, @NotNull IStubElementType nodeType) {
    super(stub, nodeType);
  }

  public StubbedPsiElementBase(@NotNull ASTNode node) {
    super(node);
  }

  /*
   * Instance Methods
   */

  @Override
  public String toString() {
    return getElementTypeImpl().toString();
  }
}
