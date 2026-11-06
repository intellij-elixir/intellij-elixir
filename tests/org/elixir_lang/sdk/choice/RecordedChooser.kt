package org.elixir_lang.sdk.choice

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.replaceService
import junit.framework.TestCase.assertEquals
import org.elixir_lang.sdk.elixir.SdkSettingsOpener
import org.elixir_lang.sdk.elixir.SettingsPage

/**
 * What the chooser was asked for, answered by [answer], Cancel unless given, and the settings pages opened, until
 * [disposable] goes.
 */
internal class RecordedChooser(disposable: Disposable, answer: (SdkChoiceState) -> SdkChoiceEntry? = { null }) {
    val chooserAsked = mutableListOf<List<Module>>()
    val settingsOpened = mutableListOf<Pair<Project?, SettingsPage>>()

    init {
        val application = ApplicationManager.getApplication()
        application.replaceService(SdkChoice::class.java, SdkChoice { _, state -> chooserAsked += state.modules; answer(state) }, disposable)
        application.replaceService(SdkSettingsOpener::class.java, object : SdkSettingsOpener {
            override fun open(event: AnActionEvent, page: SettingsPage) {
                settingsOpened += event.project to page
            }

            override fun targetName(): String = "Recorded"
        }, disposable)
    }

    fun assertChooserAskedFor(module: Module) {
        assertEquals(listOf(listOf(module)), chooserAsked)
        assertEquals("no settings opened", emptyList<Pair<Project?, SettingsPage>>(), settingsOpened)
    }
}

/** An event for [action] in [project], with [module] as the context's module. */
internal fun moduleEvent(action: AnAction, project: Project, module: Module?): AnActionEvent {
    val context = SimpleDataContext.builder()
        .add(CommonDataKeys.PROJECT, project)
        .add(PlatformCoreDataKeys.MODULE, module)
        .build()
    return TestActionEvent.createTestEvent(action, context)
}
