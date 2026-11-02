package org.elixir_lang.ui.ultimate

import com.intellij.driver.sdk.step
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.common.runToolWindow
import com.intellij.driver.sdk.ui.components.common.toolwindows.projectView
import com.intellij.driver.sdk.ui.components.common.welcomeScreen
import com.intellij.driver.sdk.ui.components.elements.balloon
import com.intellij.driver.sdk.ui.components.elements.popupMenu
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import org.elixir_lang.notification.setup_sdk.Notifier.MIX_DEPS_OUTDATED_TITLE
import org.elixir_lang.ui.framework.core.*
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.io.path.Path
import kotlin.io.path.name
import kotlin.time.Duration.Companion.minutes

/**
 * Integration test for Elixir project import in IntelliJ IDEA, in free mode:
 * 1. SDK configuration (Erlang and Elixir)
 * 2. Project import
 * 3. Mix deps installation
 * 4. Running tests via the IDE
 */
class UltimateProjectImportTest {
    /** The notification names the project's root. */
    private val depsOutdated = "$MIX_DEPS_OUTDATED_TITLE (${Path(IdeTestContext.projectPath).name})"

    @Test
    fun `import Elixir project and install its deps`() {
        step("Run test: import Elixir project and install its deps") {
            val ctx = IdeTestContext.setupTestContext("UltimateProjectImportTest")

            ctx.runIdeWithDriver().useDriverAndCloseIde {
                    ensureWelcomeScreenInForeground()

                    welcomeScreen {
                        addSdkFromDisk(
                            "Add Erlang SDK for Elixir SDK from disk",
                            IdeTestContext.erlangSdkPath,
                            "Select Home Directory for Erlang SDK for Elixir SDK"
                        )

                        addSdkFromDisk(
                            "Add Elixir SDK from disk",
                            IdeTestContext.elixirSdkPath,
                            "Select Home Directory for Elixir SDK"
                        )
                    }
                    openProjectAt(IdeTestContext.projectPath)
                    ensureIdeInForeground()
                    assignElixirSdk()
                    step("Install Mix deps") {
                        ideFrame {
                            val notificationBalloon = balloon(depsOutdated)
                            // Notification actions appear as ActionLink components in the balloon
                            val installButton = notificationBalloon.x { byVisibleText("Install deps") }
                            installButton.click()
                            waitUntilReady()
                        }
                    }

                    step("Run ExUnit tests") {
                        ideFrame {
                            projectView {
                                projectViewTree.rightClickPath(".", "test", fullMatch = false)
                            }
                            popupMenu().selectContains("Run 'Mix ExUnit")

                            // Wait for test runner to open and tests to execute
                            waitUntilReady(5.minutes)

                            runToolWindow {
                                waitContainsText("tests passed", null, true, 5.minutes)
                            }
                        }
                    }
                    step("Verify deps watcher isn't showing anymore") {
                        ideFrame {
                            waitUntilReady()
                            val notificationBalloon = balloon(depsOutdated)
                            assertTrue(notificationBalloon.notPresent())
                        }
                    }
                }
        }
    }
}
