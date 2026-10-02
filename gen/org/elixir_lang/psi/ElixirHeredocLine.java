// This is a generated file. Not intended for manual editing.
package org.elixir_lang.psi;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElement;

public interface ElixirHeredocLine extends HeredocLineable {

  @Nullable
  ElixirEscapedEOL getEscapedEOL();

  @NotNull
  ElixirHeredocLineBody getHeredocLineBody();

  @NotNull
  ElixirHeredocLinePrefix getHeredocLinePrefix();

  Body getBody();

}
