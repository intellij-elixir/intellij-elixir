package org.elixir_lang.mix.importWizard.conversion

import com.intellij.conversion.ConversionProcessor
import com.intellij.conversion.ModuleSettings
import org.elixir_lang.jps.shared.ElixirModuleTypeId.ELIXIR_MODULE_TYPE_ID

class ModuleSettings : ConversionProcessor<ModuleSettings>() {
    override fun isConversionNeeded(moduleSettings: ModuleSettings) =
            moduleSettings.moduleType == ELIXIR_MODULE_TYPE_ID &&
        parentElement(moduleSettings)?.getChild(EXCLUDE_OUTPUT_TAG) != null

    override fun process(moduleSettings: ModuleSettings) {
        parentElement(moduleSettings)!!.removeChild(EXCLUDE_OUTPUT_TAG)
    }

    companion object {
        // `JpsJavaModelSerializerExtension.EXCLUDE_OUTPUT_TAG`, which is internal API.
        private const val EXCLUDE_OUTPUT_TAG = "exclude-output"

        private fun parentElement(moduleSettings: ModuleSettings) =
            moduleSettings.getComponentElement(ModuleSettings.MODULE_ROOT_MANAGER_COMPONENT)
    }
}
