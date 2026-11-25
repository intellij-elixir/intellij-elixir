package org.elixir_lang.ui.code_intelligence

import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.step
import com.intellij.driver.sdk.ui.components.common.codeEditor
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.waitFor
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.project.LocalProjectInfo
import org.elixir_lang.ui.framework.core.IdeTestContext
import org.elixir_lang.ui.framework.core.INDEXING_TIMEOUT
import org.elixir_lang.ui.framework.core.NoScreen
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** That a run reaches an editor with no screen and no key, and that a browser opened from it goes to the sink. No SDK. */
class NoScreenProofTest {
    @Test
    @EnabledOnOs(OS.LINUX)
    fun `the browser route reaches the sink under xvfb-run`() = NoScreen.proveBrowserRoute()

    @Test
    fun `IntelliJ IDEA reaches an editor in free mode`(@TempDir project: Path) {
        project.resolve("lib").createDirectories()
        project.resolve("lib/a.ex").writeText("defmodule A do\nend\n")

        IdeTestContext.setupTestContext("NoScreenProof", project = LocalProjectInfo(project))
            .runIdeWithDriver(runTimeout = 10.minutes)
            .useDriverAndCloseIde {
                waitForIndicators(INDEXING_TIMEOUT)
                openFile("lib/a.ex")
                step("Type into the editor") {
                    ideFrame {
                        codeEditor {
                            click()
                            keyboard { typeText("# typed") }
                            // The keys reach the editor after `typeText` returns.
                            waitFor("the editor to hold the typed text", 10.seconds, errorMessage = { "the editor holds: $text" }) {
                                text.contains("# typed")
                            }
                        }
                    }
                }
            }
    }
}
