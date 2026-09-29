package org.elixir_lang.structure_view.element;

import com.intellij.ide.util.treeView.smartTree.TreeElement;
import com.intellij.navigation.ItemPresentation;
import com.intellij.psi.ElementDescriptionLocation;
import com.intellij.usageView.UsageViewTypeLocation;
import org.elixir_lang.navigation.item_presentation.Parent;
import org.elixir_lang.psi.call.Call;
import org.elixir_lang.structure_view.element.modular.Modular;
import org.elixir_lang.structure_view.element.modular.Module;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static org.elixir_lang.psi.call.name.Function.QUOTE;
import static org.elixir_lang.psi.call.name.Module.KERNEL;

/**
 * A `quote ... do ... end` block
 */
public class Quote extends Element<Call> {
    /*
     * Fields
     */

    @Nullable
    public final Presentable parent;

    /*
     * Static Methods
     */

    public static String elementDescription(Call call, ElementDescriptionLocation location) {
        String elementDescription = null;

        if (location == UsageViewTypeLocation.INSTANCE) {
            elementDescription = "quote";
        }

        return elementDescription;
    }

    /*
     * Constructors
     */

    /**
     * Quote in top-level, outside of any Module
     */
    public Quote(@NotNull Call call) {
        this((Modular) null, call);
    }

    /**
     * Quote in body of {@code defmodule} or another {@code quote}.
     *
     * @param modular Direct parent module or quote of {@code call}
     */
    public Quote(@Nullable Modular modular, @NotNull Call call) {
        super(call);
        this.parent = modular;
    }

    /**
     * Quote in body of {@code CallDefinitionClause} as is common is `defmacro __using__(_) do ... end`
     *
     * @param callDefinitionClause function definition clause in which {@code call} is.
     */
    public Quote(@NotNull CallDefinitionClause callDefinitionClause, Call call) {
        super(call);
        this.parent = callDefinitionClause;
    }

    /*
     * Methods
     */

    /**
     * Returns the presentation of the tree element.
     *
     * @return the element presentation.
     */
    @NotNull
    @Override
    public ItemPresentation getPresentation() {
        String location = null;

        if (parent != null) {
            ItemPresentation parentItemPresentation = parent.getPresentation();

            if (parentItemPresentation instanceof Parent) {
                Parent parentParentPresentation = (Parent) parentItemPresentation;
                location = parentParentPresentation.getLocatedPresentableText();
            }
        }

        return new org.elixir_lang.navigation.item_presentation.Quote(location, navigationItem);
    }

    /**
     * Returns the list of children of the tree element.
     *
     * @return the list of children.
     */
    @NotNull
    @Override
    public TreeElement[] getChildren() {
        Modular modular = modular();
        TreeElement[] children;

        if (modular != null) {
            children = Module.Companion.callChildren(modular, navigationItem);
        } else {
            children = new TreeElement[0];
        }

        return children;
    }

    @Nullable
    public Modular modular() {
        Modular modular = null;

        if (parent instanceof CallDefinitionClause) {
            CallDefinitionClause callDefinitionClause = (CallDefinitionClause) parent;
            modular = new org.elixir_lang.structure_view.element.modular.Quote(callDefinitionClause);
        } else if (parent instanceof Modular) {
            modular = (Modular) parent;
        }

        return modular;
    }
}
