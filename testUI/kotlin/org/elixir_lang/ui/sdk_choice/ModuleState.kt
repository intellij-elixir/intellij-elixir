package org.elixir_lang.ui.sdk_choice

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.client.utility
import com.intellij.driver.sdk.Module
import com.intellij.driver.sdk.getModules
import org.elixir_lang.ui.framework.core.TableSdk

@Remote("com.intellij.openapi.roots.ModuleRootManager")
internal interface ModuleRootsRef {
    fun getInstance(module: Module): ModuleRootsRef
    fun getSdk(): TableSdk?
}

/** The open project's one module. */
internal fun Driver.module(): Module = getModules().single()

/** The name of the module's SDK, its own or the project's it inherits; null when it has none. */
internal fun Driver.moduleSdk(): String? = withReadAction { utility(ModuleRootsRef::class).getInstance(module()).getSdk()?.getName() }
