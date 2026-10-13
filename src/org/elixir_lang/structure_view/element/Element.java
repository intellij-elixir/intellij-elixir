package org.elixir_lang.structure_view.element;

import com.intellij.ide.structureView.StructureViewTreeElement;
import com.intellij.navigation.NavigationItem;
import com.intellij.platform.backend.navigation.NavigationRequest;
import com.intellij.psi.NavigatablePsiElement;
import com.intellij.util.OpenSourceUtil;
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread;
import com.intellij.util.concurrency.annotations.RequiresReadLock;
import org.elixir_lang.psi.NamedElement;
import org.jetbrains.annotations.Nullable;

public abstract class Element<T extends NavigatablePsiElement> implements StructureViewTreeElement, NavigationItem {
    /*
     * Private Instance Fields
     */

    protected T navigationItem;

    /*
     * Constructors
     */

    public Element(T navigationItem) {
        this.navigationItem = navigationItem;
    }

    /*
     * Public Instance Methods
     */

    /**
     * @return <code>false</code> if navigation is not possible for any reason.
     */
    @Override
    public boolean canNavigate() {
        return navigationItem.canNavigate();
    }

    /**
     * @return <code>false</code> if navigation to source is not possible for any reason.
     * Source means some kind of editor
     */
    @Override
    public boolean canNavigateToSource() {
        return navigationItem.canNavigateToSource();
    }

    /**
     * The name of the {@link #navigationItem}.
     *
     * @return the {@link NamedElement#getName()} if {@link #navigationItem} is a {@link NamedElement}; otherwise,
     *   {@code null}.
     */
    @Nullable
    @Override
    public String getName() {
        String name = null;

        if (navigationItem instanceof NamedElement) {
            NamedElement namedElement = (NamedElement) navigationItem;
            name = namedElement.getName();
        }

        return name;
    }

    /**
     * Returns the data object (usually a PSI element) corresponding to the
     * structure view element.
     *
     * @return the data object instance.
     */
    @Override
    public Object getValue() {
        return navigationItem;
    }

    /**
     * The tree nodes forward navigation here, so it must not call the target's {@code navigate}, which is
     * override-only.
     */
    @Override
    public void navigate(boolean requestFocus) {
        OpenSourceUtil.navigate(requestFocus, navigationItem);
    }

    @SuppressWarnings("UnstableApiUsage")
    @RequiresReadLock
    @RequiresBackgroundThread
    @Nullable
    @Override
    public NavigationRequest navigationRequest() {
        return navigationItem.navigationRequest();
    }
}
