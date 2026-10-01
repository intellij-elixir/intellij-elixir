// This is a generated file. Not intended for manual editing.
package org.elixir_lang.psi;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElement;

public interface ElixirInterpolatedHeredocLine extends HeredocLineable {

  @Nullable
  ElixirEscapedEOL getEscapedEOL();

  @NotNull
  ElixirHeredocLinePrefix getHeredocLinePrefix();

  @NotNull
  ElixirInterpolatedHeredocLineBody getInterpolatedHeredocLineBody();

  Body getBody();

}
