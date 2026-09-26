package org.elixir_lang.module;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleConfigurationEditor;
import com.intellij.openapi.module.ModuleType;
import com.intellij.openapi.roots.ui.configuration.ClasspathEditor;
import com.intellij.openapi.roots.ui.configuration.ModuleConfigurationEditorProvider;
import com.intellij.openapi.roots.ui.configuration.ModuleConfigurationState;
import com.intellij.openapi.roots.ui.configuration.OutputEditor;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

import static org.elixir_lang.jps.shared.ElixirModuleTypeId.ELIXIR_MODULE_TYPE_ID;

/**
 * Created by zyuyou on 15/6/5.
 *
 */
public class DefaultModuleEditorsProvider implements ModuleConfigurationEditorProvider{

  @Override
  public @NotNull ModuleConfigurationEditor @NotNull [] createEditors(@NotNull ModuleConfigurationState state) {
    Module module = state.getCurrentRootModel().getModule();
    if(ModuleType.get(module).getId().equals(ELIXIR_MODULE_TYPE_ID)){
      return new ModuleConfigurationEditor[]{
          new ElixirContentEntriesEditor(module.getName(), state),
          new OutputEditorEx(state),
          new ClasspathEditor(state)
      };
    }
    return ModuleConfigurationEditor.EMPTY;
  }

  public static class OutputEditorEx extends OutputEditor{
    protected OutputEditorEx(ModuleConfigurationState state){
      super(state);
    }

    protected JComponent createComponentImpl(){
      JComponent component =  super.createComponentImpl();
      component.remove(1);  // todo: looks ugly
      return component;
    }
  }
}
