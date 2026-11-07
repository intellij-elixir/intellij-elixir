package org.elixir_lang.sdk.choice

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.util.concurrency.annotations.RequiresEdt
import org.elixir_lang.tool_manager.ToolManagerOptIns

/** A builder that takes the chooser step's pick and writes it to the modules it commits. */
interface TakesSdkPick {
    /** The Elixir SDK the builder's commit writes to the modules it makes. */
    var elixirSdk: Sdk?

    /** The tool manager opt-ins the builder's commit writes with [elixirSdk]. */
    var toolManagerOptIns: ToolManagerOptIns?

    /** Takes the chooser step's [pick] as [elixirSdk] and [toolManagerOptIns], clearing both when there is none. */
    fun takePick(pick: SdkChoicePick?) {
        elixirSdk = pick?.entry?.sdk
        toolManagerOptIns = pick?.optIns
    }

    /** Writes [elixirSdk] and [toolManagerOptIns] to [modules] through [writePick]; nothing when there is no SDK. */
    @RequiresEdt
    fun writePickTo(project: Project, modules: List<Module>) {
        elixirSdk?.let { writePick(project, modules, it, toolManagerOptIns) }
    }
}
