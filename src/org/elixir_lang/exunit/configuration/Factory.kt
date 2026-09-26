package org.elixir_lang.exunit.configuration

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.project.Project
import org.elixir_lang.exunit.Configuration

object Factory : ConfigurationFactory(Type.INSTANCE) {
    override fun createTemplateConfiguration(project: Project): RunConfiguration =
            Configuration(Type.TYPE_NAME, project)

    override fun getId(): String = Type.TYPE_NAME
}
