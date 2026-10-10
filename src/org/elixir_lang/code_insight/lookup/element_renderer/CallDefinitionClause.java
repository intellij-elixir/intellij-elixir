package org.elixir_lang.code_insight.lookup.element_renderer;

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementPresentation;
import com.intellij.navigation.ItemPresentation;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.psi.PsiElement;
import com.intellij.ui.RowIcon;
import org.elixir_lang.Icons;
import org.elixir_lang.beam.psi.CallDefinition;
import org.elixir_lang.call.Visibility;
import org.elixir_lang.code_insight.Signature;
import org.elixir_lang.psi.call.Call;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class CallDefinitionClause extends com.intellij.codeInsight.lookup.LookupElementRenderer<LookupElement> {
    /*
     * Fields
     */

    @NotNull
    private final String name;

    /*
     * Constructors
     */

    public CallDefinitionClause(@NotNull String name) {
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

        if (psiElement == null) {
            renderObject(element);
        } else {
            renderPsiElement(psiElement, presentation);
        }
    }

    /*
     * Private Instance Methods
     */

    private void renderCall(@NotNull Call call, @NotNull LookupElementPresentation presentation) {
        if (org.elixir_lang.psi.CallDefinitionClause.is(call)) {
            renderCallDefinitionClause(call, presentation);
        }
    }

    private void renderCallDefinitionClause(@NotNull Call call, @NotNull LookupElementPresentation presentation) {
        org.elixir_lang.structure_view.element.CallDefinitionClause structureView =
                org.elixir_lang.structure_view.element.CallDefinitionClause.Companion.fromCall(call);

        if (structureView != null) {
            renderStructureView(structureView, presentation);
        }
    }

    private void renderItemPresentation(@NotNull ItemPresentation itemPresentation,
                                        @NotNull LookupElementPresentation lookupElementPresentation) {
        lookupElementPresentation.setIcon(itemPresentation.getIcon(true));
        String presentableText = itemPresentation.getPresentableText();

        if (presentableText != null) {
            int nameLength = name.length();
            int presentableTextLength = presentableText.length();

            if (nameLength <= presentableTextLength) {
                lookupElementPresentation.appendTailText(presentableText.substring(nameLength), true);
            }
        }

        String locationString = itemPresentation.getLocationString();

        if (locationString != null) {
            lookupElementPresentation.appendTailText(" (" + locationString + ")", false);
        }
    }

    private void renderObject(@NotNull LookupElement lookupElement) {
        Logger logger = Logger.getInstance(CallDefinitionClause.class);

        Object object = lookupElement.getObject();
        String title  = "CallDefinitionClause render called on LookupElement with null getPsiElement";
        String message = "## name\n" +
                "\n" +
                "```\n" +
                name + "\n" +
                "```\n" +
                "\n" +
                "## getObject()\n"+
                "\n" +
                "### toString()\n" +
                "\n" +
                "```\n" +
                object + "\n" +
                "```\n" +
                "\n" +
                "### getClass().getName()\n" +
                "\n" +
                "```\n" +
                object.getClass().getName() + "\n" +
                "```\n";

        logger.error(message, new Throwable(title));
    }

    private void renderPsiElement(@NotNull PsiElement psiElement, @NotNull LookupElementPresentation presentation) {
        if (psiElement instanceof Call) {
            renderCall((Call) psiElement, presentation);
        } else if (psiElement instanceof CallDefinition) {
            renderBeamCallDefinition((CallDefinition) psiElement, presentation);
        }
    }

    private void renderBeamCallDefinition(
            @NotNull CallDefinition callDefinition,
            @NotNull LookupElementPresentation presentation) {
        List<String> parameters = Signature.Companion.parametersAt(callDefinition);
        presentation.appendTailText("(" + String.join(", ", parameters) + ")", true);

        // Mirror the source render path's icon (navigation.item_presentation.CallDefinitionHead):
        // a RowIcon of [time (function/macro), visibility, call-definition-clause].  Only exported
        // (public) definitions are offered in completion, so visibility is PUBLIC.
        RowIcon icon = new RowIcon(3);
        icon.setIcon(switch (callDefinition.getCapabilities().getPresentation()) {
            case FUNCTION -> Icons.Time.RUN;
            case MACRO, GUARD -> Icons.Time.COMPILE;
        }, 0);
        icon.setIcon(Icons.Visibility.from(Visibility.PUBLIC), 1);
        icon.setIcon(Icons.CALL_DEFINITION_CLAUSE, 2);
        presentation.setIcon(icon);
    }

    private void renderStructureView(@NotNull org.elixir_lang.structure_view.element.CallDefinitionClause structureView,
                                     @NotNull LookupElementPresentation presentation) {
        renderItemPresentation(structureView.getPresentation(), presentation);
    }
}
