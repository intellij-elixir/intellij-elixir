package org.elixir_lang.folding;

import com.intellij.application.options.editor.CodeFoldingOptionsProvider;
import com.intellij.openapi.options.BeanConfigurable;

public class OptionsProvider extends BeanConfigurable<ElixirFoldingSettings> implements CodeFoldingOptionsProvider {
    public OptionsProvider() {
        super(ElixirFoldingSettings.getInstance());
        ElixirFoldingSettings settings = getInstance();
        checkBox("Elixir Module directive (`alias`, `import`, `require` or `use`) groups", settings::isCollapseElixirModuleDirectiveGroups, value -> settings.COLLAPSE_ELIXIR_MODULE_DIRECTIVE_GROUPS = value);
    }
}
