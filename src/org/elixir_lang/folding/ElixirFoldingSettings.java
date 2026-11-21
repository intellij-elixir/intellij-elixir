package org.elixir_lang.folding;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;

/**
 * @see <a href="https://github.com/JetBrains/intellij-community/blob/22ad327271fbf0953803463ab132ba8253c1b496/python/src/com/jetbrains/python/PythonFoldingSettings.java">com.jetbrains.python.PythonFoldingSettings</a>
 */
@State(
        name = "ElixirFoldingSettings",
        storages = {
                @Storage(value = "editor.codeinsight.xml")
        }
)
public class ElixirFoldingSettings implements PersistentStateComponent<ElixirFoldingSettings> {
    /*
     * Fields
     */
    public boolean COLLAPSE_ELIXIR_MODULE_DIRECTIVE_GROUPS = false;

    @NotNull
    @Override
    public ElixirFoldingSettings getState() {
        return this;
    }

    @NotNull
    public static ElixirFoldingSettings getInstance() {
        return ApplicationManager.getApplication().getService(ElixirFoldingSettings.class);
    }

    @Override
    public void loadState(@NotNull ElixirFoldingSettings state) {
        XmlSerializerUtil.copyBean(state, this);
    }

    public boolean isCollapseElixirModuleDirectiveGroups() {
        return COLLAPSE_ELIXIR_MODULE_DIRECTIVE_GROUPS;
    }
}
