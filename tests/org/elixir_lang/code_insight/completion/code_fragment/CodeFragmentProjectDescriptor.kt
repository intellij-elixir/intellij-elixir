package org.elixir_lang.code_insight.completion.code_fragment

import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.roots.OrderRootType
import com.intellij.testFramework.LightProjectDescriptor
import org.elixir_lang.expander.appEbins
import org.elixir_lang.snapshot.LegElixirProjectDescriptor
import org.elixir_lang.snapshot.addLegElixir
import org.elixir_lang.snapshot.addLibrary

/** [LegElixirProjectDescriptor]'s project, plus a library of each OTP application's `ebin`, as the plugin in use has. */
object CodeFragmentProjectDescriptor : LightProjectDescriptor() {
    override fun configureModule(module: Module, model: ModifiableRootModel, contentEntry: ContentEntry) {
        addLegElixir(module, model)
        addLibrary(module, model, "Erlang/OTP", appEbins("ERLANG_SDK_HOME").sortedBy { it.path }.map { it to OrderRootType.CLASSES })
    }
}
