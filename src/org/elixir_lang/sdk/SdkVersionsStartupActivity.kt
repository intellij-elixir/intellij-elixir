package org.elixir_lang.sdk

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

internal class SdkVersionsStartupActivity : ProjectActivity, DumbAware {
    override suspend fun execute(project: Project) {
        service<SdkVersionWatchLifetime>()
        SdkVersionsFiller.fillUsedBy(project)
    }
}

/** Installs [SdkVersionWatchService] once per application, for the application's lifetime. */
@Service
internal class SdkVersionWatchLifetime : Disposable {
    init {
        SdkVersionWatchService.install(this)
    }

    override fun dispose() {}
}
