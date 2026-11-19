package org.elixir_lang.structure_view.element;

import com.intellij.ide.util.treeView.smartTree.TreeElement;
import com.intellij.navigation.ItemPresentation;
import com.intellij.psi.ElementDescriptionLocation;
import com.intellij.usageView.UsageViewTypeLocation;
import org.elixir_lang.psi.call.Call;
import org.elixir_lang.structure_view.element.structure.Structure;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A `defexception` with its fields and the callbacks `exception/1` and `message/1` if overridden.
 */
public class Exception extends Element<Call> {
    /*
     * Fields
     */

    @Nullable
    private List<CallDefinition> callbacks = null;

    /*
     * Static Methods
     */

    public static String elementDescription(Call call, ElementDescriptionLocation location) {
        String elementDescription = null;

        if (location == UsageViewTypeLocation.INSTANCE) {
            elementDescription = "exception";
        }

        return elementDescription;
    }


    /*
     * Constructors
     */

    public Exception(@NotNull Call call) {
        super(call);
    }

    /*
     * Instance Methods
     */

    /**
     * Adds callback function
     *
     * @param callback the callback function: either exception/1 or message/1.
     */
    @Contract(pure = false)
    public void callback(@NotNull final CallDefinition callback) {
        assert callback.getArity() == 1;
        assert callback.time() == Timed.Time.RUN;

        String callbackName = callback.name();
        assert callbackName.equals("exception") || callbackName.equals("message");

        if (callbacks == null) {
            callbacks = new ArrayList<CallDefinition>();
        }

        callbacks.add(callback);
    }

    /**
     * Returns the list of children of the tree element.
     *
     * @return the list of children.
     */
    @NotNull
    @Override
    public TreeElement[] getChildren() {
        List<TreeElement> childList = new ArrayList<TreeElement>();

        childList.add(
                new Structure(navigationItem)
        );

        if (callbacks != null) {
            childList.addAll(callbacks);
        }

        return childList.toArray(new TreeElement[childList.size()]);
    }

    /**
     * Returns the presentation of the tree element.
     *
     * @return the element presentation.
     */
    @NotNull
    @Override
    public ItemPresentation getPresentation() {
        ModuleQualifiedName qualifiedName = ModuleQualifiedName.of(navigationItem, "exception");

        return new org.elixir_lang.navigation.item_presentation.Exception(
                qualifiedName.getLocation(),
                qualifiedName.getName()
        );
    }
}
