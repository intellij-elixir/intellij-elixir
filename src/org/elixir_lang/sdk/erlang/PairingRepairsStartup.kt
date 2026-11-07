package org.elixir_lang.sdk.erlang

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** Starts [PairingRepairs] once a project opens: SDKs loaded with the table fire no `jdkAdded`. */
internal class PairingRepairsStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        PairingRepairs.getInstance().ensureStarted()
    }
}
