package org.elixir_lang.jps.builder.tool_manager;

import com.intellij.util.xmlb.XmlSerializer;
import org.elixir_lang.jps.shared.tool_manager.ToolManagerSettingsState;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jps.model.JpsProject;
import org.jetbrains.jps.model.serialization.JpsProjectExtensionSerializer;

public class SettingsSerializer extends JpsProjectExtensionSerializer {
    public SettingsSerializer() {
        super(ToolManagerSettingsState.FILE_NAME, ToolManagerSettingsState.COMPONENT_NAME);
    }

    @Override
    public void loadExtension(@NotNull JpsProject jpsProject, @NotNull Element componentTag) {
        SettingsExtension.setState(jpsProject, XmlSerializer.deserialize(componentTag, ToolManagerSettingsState.class));
    }
}
