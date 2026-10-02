package org.elixir_lang.structure_view.element;

import com.intellij.ide.util.treeView.smartTree.TreeElement;
import com.intellij.navigation.ItemPresentation;
import com.intellij.psi.ElementDescriptionLocation;
import com.intellij.usageView.UsageViewTypeLocation;
import org.elixir_lang.model.psi.FunctionArityKeywordPair;
import org.elixir_lang.navigation.item_presentation.Parent;
import org.elixir_lang.psi.QuotableKeywordList;
import org.elixir_lang.psi.QuotableKeywordPair;
import org.elixir_lang.psi.call.Call;
import org.elixir_lang.structure_view.element.modular.Modular;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

import static org.elixir_lang.psi.call.name.Function.DEFOVERRIDABLE;
import static org.elixir_lang.psi.call.name.Module.KERNEL;
import static org.elixir_lang.psi.impl.call.CallImplKt.keywordArguments;

public class Overridable extends Element<Call> {
    /*
     * Fields
     */

    @NotNull
    private final Modular modular;

    /*
     * Static Methods
     */

    public static String elementDescription(ElementDescriptionLocation location) {
        String elementDescription = null;

        if (location == UsageViewTypeLocation.INSTANCE) {
            elementDescription = "overridable";
        }

        return elementDescription;
    }

    public static boolean is(Call call) {
        return call.isCalling(KERNEL, DEFOVERRIDABLE, 1);
    }

    /*
     * Constructors
     */

    public Overridable(@NotNull Modular modular, @NotNull Call call) {
        super(call);
        this.modular = modular;
    }

    /**
     * Returns the list of children of the tree element.
     *
     * @return the array of children.
     */
    @Override
    public TreeElement @NotNull [] getChildren() {
        QuotableKeywordList keywordArguments = keywordArguments(navigationItem);
        TreeElement[] children;

        if (keywordArguments != null) {
            List<QuotableKeywordPair> quotableKeywordPairList = keywordArguments.quotableKeywordPairList();
            List<TreeElement> treeElementList = new ArrayList<>(quotableKeywordPairList.size());

            for (QuotableKeywordPair quotableKeywordPair : quotableKeywordPairList) {
                String name = FunctionArityKeywordPair.INSTANCE.nameFromKey(quotableKeywordPair);
                Integer arity = FunctionArityKeywordPair.INSTANCE.arityFromValue(quotableKeywordPair.getKeywordValue());

                boolean overridable = true;
                //noinspection ConstantConditions
                treeElementList.add(
                        new CallReference(modular, quotableKeywordPair, Timed.Time.RUN, overridable, name, arity)
                );
            }

            children = treeElementList.toArray(new TreeElement[0]);
        } else {
            children = new TreeElement[0];
        }

        return children;
    }

    /**
     * Returns the presentation of the tree element.
     *
     * @return the element presentation.
     */
    @NotNull
    @Override
    public ItemPresentation getPresentation() {
        ItemPresentation itemPresentation = modular.getPresentation();
        String location = null;

        if (itemPresentation instanceof Parent parentPresenation) {
            location = parentPresenation.getLocatedPresentableText();
        }

        return new org.elixir_lang.navigation.item_presentation.Overridable(location);
    }


}
