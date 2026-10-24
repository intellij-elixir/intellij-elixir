package org.elixir_lang.jps.builder.tool_manager;

import org.elixir_lang.jps.shared.tool_manager.ToolManagerSettingsState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jps.model.JpsElementChildRole;
import org.jetbrains.jps.model.JpsProject;
import org.jetbrains.jps.model.ex.JpsCompositeElementBase;
import org.jetbrains.jps.model.ex.JpsElementChildRoleBase;

/** The project's tool manager settings, as the IDE persisted them. */
public class SettingsExtension extends JpsCompositeElementBase<SettingsExtension> {
    private static final JpsElementChildRole<SettingsExtension> ROLE = JpsElementChildRoleBase.create("ToolManager");

    @NotNull
    private final ToolManagerSettingsState state;

    SettingsExtension(@NotNull ToolManagerSettingsState state) {
        this.state = state;
    }

    /** The settings, or the defaults, which apply no tool manager, when the project has none saved. */
    @NotNull
    public static ToolManagerSettingsState getState(@NotNull JpsProject project) {
        SettingsExtension extension = project.getContainer().getChild(ROLE);

        return extension != null ? extension.state : new ToolManagerSettingsState();
    }

    static void setState(@NotNull JpsProject project, @NotNull ToolManagerSettingsState state) {
        project.getContainer().setChild(ROLE, new SettingsExtension(state));
    }

    @NotNull
    @Override
    // JPS API still requires createCopy despite removal deprecation.
    @SuppressWarnings("removal")
    public SettingsExtension createCopy() {
        return new SettingsExtension(state.copy());
    }
}
