package org.elixir_lang.facet.configurable

import com.intellij.application.options.ModuleAwareProjectConfigurable
import com.intellij.facet.FacetManager
import com.intellij.ide.DataManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.options.ex.Settings
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import org.elixir_lang.Facet
import org.elixir_lang.facet.Configurable
import org.elixir_lang.jps.shared.ElixirModuleTypeId.ELIXIR_MODULE_TYPE_ID
import org.elixir_lang.sdk.ProcessOutput
import org.elixir_lang.sdk.elixir.ElixirSdkAssignment
import org.elixir_lang.settings.SettingsPageId
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

class Project(project: Project) : ModuleAwareProjectConfigurable<Configurable>(project, "Elixir", null) {
    override fun isSuitableForModule(module: Module): Boolean =
        // CANNOT use ModuleType.is(module, ElixirModuleType.getInstance()) as ElixirModuleType depends
        // on JavaModuleBuilder and so is only available in IntelliJ.
        //
        // ELIXIR_MODULE modules are normally configured via the module SDK in Project Structure,
        // which only exists in rich IDEs, so they are excluded from this facet-based per-module SDK
        // page there. But a project created in IntelliJ keeps its ELIXIR_MODULE module ids when
        // opened in a small IDE (RubyMine, etc.), where there is no module-SDK UI - the facet SDK is
        // then the only per-module mechanism, so include every module when running in a small IDE.
        ProcessOutput.isSmallIde || ModuleType.get(module).id != ELIXIR_MODULE_TYPE_ID

    override fun createComponent(): JComponent {
        // The per-module SDK dropdowns read the shared ProjectSdksModel, which SdksService populates
        // once (from the JDK table, with an open project) - no reset needed here.
        val base = super.createComponent()
        return JPanel(BorderLayout()).apply {
            add(createHeaderPanel(), BorderLayout.NORTH)
            base?.let { add(it, BorderLayout.CENTER) }
        }
    }

    /**
     * Guidance shown at the top of the Elixir settings page: SDKs are added and edited on the child
     * pages, and this page assigns one to each module. The links navigate to those child pages.
     */
    private fun createHeaderPanel(): JComponent {
        val intro = JBLabel(
            "<html>Elixir and Erlang SDKs are added and edited on the pages below. " +
                "Add or edit them there, then assign an Elixir SDK to each module on this page.</html>"
        ).apply { alignmentX = Component.LEFT_ALIGNMENT }

        val linksRow = JPanel(FlowLayout(FlowLayout.LEFT, 12, 0)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            add(settingsLink("Manage Elixir SDKs", SettingsPageId.ELIXIR_SDKS.id))
            add(settingsLink("Manage Internal Erlang SDKs", SettingsPageId.ERLANG_SDKS.id))
        }

        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8, 8, 12, 8)
            add(intro)
            add(linksRow)
        }
    }

    /** A hyperlink that navigates to the child configurable with [configurableId] within Settings. */
    private fun settingsLink(text: String, configurableId: String): HyperlinkLabel =
        HyperlinkLabel(text).apply {
            addHyperlinkListener {
                val settings = Settings.KEY.getData(DataManager.getInstance().getDataContext(this))
                settings?.find(configurableId)?.let { settings.select(it) }
            }
        }

    override fun createModuleConfigurable(module: Module): Configurable {
        return object : Configurable(module) {
            override fun applySdk(sdk: Sdk?) {
                WriteAction.run<Throwable> {
                    if (sdk != null) {
                        ElixirSdkAssignment.assign(module, sdk)
                    } else {
                        FacetManager.getInstance(module).getFacetByType(Facet.ID)?.sdk = null
                    }
                }
            }

            override fun initSdk(): Sdk? = Facet.sdk(module)
        }
    }
}
