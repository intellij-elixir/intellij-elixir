package org.elixir_lang.ui.framework.core

import com.intellij.ide.starter.ci.CIServer
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.opentest4j.AssertionFailedError

/** Which errors the CI server [IdeTestContext] binds fails a test on, with no IDE. */
class IdeTestContextTest {
    private val server: CIServer = IdeTestContext.let { CIServer.instance }

    private val dispatch = """
        |	at java.desktop/java.awt.EventQueue.dispatchEventImpl(EventQueue.java:732)
        |	at java.desktop/java.awt.EventQueue.dispatchEvent(EventQueue.java:711)
        |	at com.intellij.ide.IdeEventQueue._dispatchEvent(IdeEventQueue.kt:574)
        |	at com.intellij.ide.IdeEventQueue.dispatchEvent(IdeEventQueue.kt:416)
        |	at com.intellij.openapi.ui.DialogWrapper.doShowAndGet(DialogWrapper.java:1640)
    """.trimMargin()

    private val platformThrow = """
        |	at com.intellij.openapi.application.impl.ApplicationImpl.assertWriteAccessAllowed(ApplicationImpl.java:1054)
        |	at com.intellij.openapi.projectRoots.ui.SdkEditor.doSetHomePath(SdkEditor.java:398)
    """.trimMargin()

    private val pluginDialog = "\tat org.elixir_lang.sdk.choice.SdkRowsDialog.showAndAnswer(SdkRowsDialog.kt:615)"
    private val pluginAction = "\tat org.elixir_lang.sdk.choice.ChooseElixirSdkAction.actionPerformed(ChooseElixirSdkAction.kt:22)"
    private val pluginThrow = "\tat org.elixir_lang.sdk.choice.SdkRowsDialog.putBack(SdkRowsDialog.kt:530)"

    /** [details] reported as Starter reports an IDE error: its message names the test, not the error. */
    private fun report(details: String) =
        server.reportTestFailure(
            "a test",
            "Test: org.elixir_lang.ui.sdk_choice.RowsSdkPageTest.a test\nYou can find logs and other useful info in CI artifacts under the path a-test",
            details,
        )

    private fun assertBlamed(details: String) {
        assertThrows<AssertionFailedError> { report(details) }
    }

    private fun assertNotBlamed(details: String) {
        assertDoesNotThrow { report(details) }
    }

    @Test
    fun `plugin frames between two event dispatches, below the innermost, are not blamed`() {
        assertNotBlamed(
            """
            |java.lang.Throwable: Write access is allowed inside write-action only
            |$platformThrow
            |$dispatch
            |$pluginDialog
            |$dispatch
            |$pluginAction
            """.trimMargin(),
        )
    }

    @Test
    fun `a cause's plugin frames below its own event dispatch are not blamed`() {
        assertNotBlamed(
            """
            |java.lang.RuntimeException: Write access is allowed inside write-action only
            |	at com.intellij.openapi.application.impl.ApplicationImpl.runWriteAction(ApplicationImpl.java:900)
            |$dispatch
            |Caused by: java.lang.Throwable: Write access is allowed inside write-action only
            |$platformThrow
            |$dispatch
            |$pluginDialog
            |	... 12 more
            """.trimMargin(),
        )
    }

    @Test
    fun `a plugin frame above the event dispatch is blamed`() {
        assertBlamed(
            """
            |java.lang.IllegalStateException: no SDK
            |$pluginThrow
            |	at com.intellij.openapi.ui.ComboBox.setSelectedItem(ComboBox.java:120)
            |$dispatch
            """.trimMargin(),
        )
    }

    @Test
    fun `a cause's plugin frame above its own event dispatch is blamed`() {
        assertBlamed(
            """
            |java.lang.RuntimeException: wrapped
            |	at com.intellij.openapi.application.impl.ApplicationImpl.runWriteAction(ApplicationImpl.java:900)
            |$dispatch
            |Caused by: java.lang.IllegalStateException: no SDK
            |$pluginThrow
            |$dispatch
            |	... 12 more
            """.trimMargin(),
        )
    }

    @Test
    fun `a cause with no event dispatch of its own is read whole`() {
        assertBlamed(
            """
            |java.lang.RuntimeException: wrapped
            |	at com.intellij.openapi.application.impl.ApplicationImpl.runWriteAction(ApplicationImpl.java:900)
            |$dispatch
            |Caused by: java.lang.IllegalStateException: no SDK
            |	at com.intellij.openapi.progress.ProgressManager.checkCanceled(ProgressManager.java:200)
            |$pluginThrow
            |	... 12 more
            """.trimMargin(),
        )
    }

    @Test
    fun `a trace with no event dispatch is read whole`() {
        assertBlamed(
            """
            |java.lang.IllegalStateException: no SDK
            |	at com.intellij.openapi.progress.ProgressManager.checkCanceled(ProgressManager.java:200)
            |$pluginThrow
            |	at java.base/java.lang.Thread.run(Thread.java:1583)
            """.trimMargin(),
        )
    }

    @Test
    fun `a suppressed exception's plugin frame above its own event dispatch is blamed`() {
        assertBlamed(
            """
            |java.lang.RuntimeException: wrapped
            |	at com.intellij.openapi.application.impl.ApplicationImpl.runWriteAction(ApplicationImpl.java:900)
            |$dispatch
            |	Suppressed: java.lang.IllegalStateException: no SDK
            |	$pluginThrow
            |	$dispatch
            |		... 12 more
            """.trimMargin(),
        )
    }

    @Test
    fun `plugin frames below a module-qualified AWT event dispatch alone are not blamed`() {
        assertNotBlamed(
            """
            |java.lang.Throwable: Write access is allowed inside write-action only
            |$platformThrow
            |	at java.desktop/java.awt.EventQueue.dispatchEvent(EventQueue.java:711)
            |$pluginAction
            """.trimMargin(),
        )
    }

    @Test
    fun `plugin frames below the IDE's event dispatch alone are not blamed`() {
        assertNotBlamed(
            """
            |java.lang.Throwable: Write access is allowed inside write-action only
            |$platformThrow
            |	at com.intellij.ide.IdeEventQueue.dispatchEvent(IdeEventQueue.kt:416)
            |$pluginAction
            """.trimMargin(),
        )
    }

    @Test
    fun `an IDE error naming an Elixir install, with only platform frames, is not blamed by either reader`() {
        val error = """
            |java.lang.IllegalStateException: no home at /p/.local/share/mise/installs/elixir/1.20.4-rows
            |$platformThrow
            |$dispatch
            """.trimMargin()

        assertNotBlamed(error)
        assertFalse(blamesPlugin(error))
    }

    @Test
    fun `Starter's report of processes left running that names Elixir is blamed`() {
        assertThrows<AssertionFailedError> {
            server.reportTestFailure(
                "Unexpected running processes were detected after IDE was stopped beam.smp",
                "Unexpected running processes were detected after IDE was stopped beam.smp\n" +
                    "Process:[Name:beam.smp, Id:4242]\n\n" +
                    "/p/.local/share/mise/installs/elixir/1.20.4/bin/elixir --sname rows",
                "",
            )
        }
    }
}
