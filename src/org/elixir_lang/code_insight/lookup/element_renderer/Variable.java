package org.elixir_lang.code_insight.lookup.element_renderer;

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementPresentation;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.ui.JBColor;
import org.elixir_lang.ElixirSyntaxHighlighter;
import org.elixir_lang.Icons;
import org.elixir_lang.annotator.Parameter;
import org.elixir_lang.psi.operation.InMatch;
import org.elixir_lang.psi.operation.Match;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

import static org.elixir_lang.reference.Callable.*;

public class Variable extends com.intellij.codeInsight.lookup.LookupElementRenderer<LookupElement> {
    /*
     * Fields
     */

    @NotNull
    private final String name;

    /*
     * Constructors
     */

    public Variable(@NotNull String name) {
        this.name = name;
    }

    /*
     * Public Instance Methods
     */

    @Override
    public void renderElement(LookupElement element, LookupElementPresentation presentation) {
        presentation.setItemText(name);
        presentation.setItemTextBold(true);

        PsiElement psiElement = element.getPsiElement();

        assert psiElement != null;

        presentation.setIcon(icon(psiElement));
        presentation.setItemTextForeground(color(psiElement));

        PsiElement enclosingMatch = enclosingMatch(psiElement);

        /* A bare parameter is its own enclosing match, so the tail would only repeat the item text.
           See https://github.com/intellij-elixir/intellij-elixir/issues/496 */
        if (enclosingMatch == psiElement) {
            return;
        }

        /* Add a space between variable name and match.
           See https://github.com/intellij-elixir/intellij-elixir/issues/506 */
        presentation.appendTailText(" ", false);

        TextRange psiElementTextRange = psiElement.getTextRange();
        TextRange enclosingMatchTextRange = enclosingMatch.getTextRange();
        String enclosingMatchText = enclosingMatch.getText();

        int enclosingMatchTextRangeStartOffset = enclosingMatchTextRange.getStartOffset();
        int itemStartOffset = psiElementTextRange.getStartOffset() - enclosingMatchTextRangeStartOffset;
        String prefix = enclosingMatchText.substring(0, itemStartOffset);
        presentation.appendTailText(prefix, true);

        int itemEndOffset = psiElementTextRange.getEndOffset() - enclosingMatchTextRangeStartOffset;
        String item = enclosingMatchText.substring(itemStartOffset, itemEndOffset);
        presentation.appendTailText(item, false);

        String suffix = enclosingMatchText.substring(
                itemEndOffset,
                enclosingMatchTextRange.getEndOffset() - enclosingMatchTextRangeStartOffset
        );
        presentation.appendTailText(suffix, true);
    }

    /*
     * Private Instance Methods
     */


    @Contract(pure = true)
    @NotNull
    private Color color(@NotNull final PsiElement element) {
        Color color = null;
        Parameter parameter = new Parameter(element);
        Parameter.Type parameterType = Parameter.putParameterized(parameter).type;

        if (parameterType != null) {
            if (parameterType == Parameter.Type.VARIABLE) {
                color = ElixirSyntaxHighlighter.PARAMETER.getDefaultAttributes().getForegroundColor();
            }
        } else if (isParameterWithDefault(element)) {
            color = ElixirSyntaxHighlighter.PARAMETER.getDefaultAttributes().getForegroundColor();
        } else if (isVariable(element)) {
            color = ElixirSyntaxHighlighter.VARIABLE.getDefaultAttributes().getForegroundColor();
        }

        if (color == null) {
            color = JBColor.foreground();
        }

        return color;
    }

    @Contract(pure = true)
    @NotNull
    private PsiElement enclosingMatch(@NotNull final PsiElement ancestor) {
        PsiElement enclosingMatch = ancestor;
        PsiElement parent = ancestor.getParent();

        if (parent instanceof InMatch || parent instanceof Match) {
            enclosingMatch = parent;
        } else {
            assert ancestor != null;
        }

        return enclosingMatch;
    }

    @Contract(pure = true)
    @Nullable
    private Icon icon(@NotNull PsiElement element) {
        Icon icon = null;

        Parameter parameter = new Parameter(element);
        Parameter.Type parameterType = Parameter.putParameterized(parameter).type;

        if (parameterType != null) {
            icon = Icons.PARAMETER;
        } else {
            icon = Icons.VARIABLE;
        }

        return icon;
    }
}
