package org.elixir_lang.psi;

import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiLanguageInjectionHost;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Created by kadie.enheduanna.inanna on 2/4/15.
 */
public interface Parent extends PsiLanguageInjectionHost, PsiElement {
    @NotNull
    List<Integer> addEscapedCharacterCodePoints(@Nullable List<Integer> codePointList, @NotNull ASTNode child);

    @NotNull
    List<Integer> addEscapedEOL(@Nullable List<Integer> codePointList, @NotNull ASTNode child);

    @NotNull
    List<Integer> addEscapedTerminator(@Nullable List<Integer> codePointList, @NotNull ASTNode child);

    @NotNull
    List<Integer> addFragmentCodePoints(@Nullable List<Integer> codePointList, @NotNull ASTNode child);

    @NotNull
    List<Integer> addHexadecimalEscapeSequenceCodePoints(@Nullable List<Integer> codePointList, @NotNull ASTNode child);
}
