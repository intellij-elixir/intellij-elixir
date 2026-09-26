package org.elixir_lang.mix.configuration

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.project.Project
import org.elixir_lang.mix.Configuration

/**
 * https://github.com/ignatov/intellij-erlang/blob/master/src/org/intellij/erlang/rebar/runner/RebarRunConfigurationFactory.java
 */
object Factory : ConfigurationFactory(Type.INSTANCE) {
    override fun createTemplateConfiguration(project: Project): RunConfiguration =
            Configuration(TYPE_NAME, project, this)

    override fun getId(): String = TYPE_NAME
}
