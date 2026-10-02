package org.elixir_lang

import com.intellij.navigation.NavigationItem
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.ElementDescriptionUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.usageView.UsageViewTypeLocation
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.xdebugger.XDebuggerUtil
import org.elixir_lang.breadcrumbs.Provider
import org.elixir_lang.debugger.breakpointModuleNames
import org.elixir_lang.debugger.line_breakpoint.Type
import org.elixir_lang.goto_decompiled.Item
import org.elixir_lang.psi.QualifiableAlias
import org.elixir_lang.psi.call.Call
import java.util.concurrent.Callable
import javax.swing.SwingUtilities

/** Entries the platform can call from a thread that holds no read lock. */
class UnlockedCallersTest : PlatformTestCase() {
    fun testBreakpointModuleNames() {
        val file = myFixture.configureByText("unlocked.ex", SOURCE)

        assertEquals(
            setOf("Foo"),
            unlocked { breakpointModuleNames(project, file.virtualFile, SOURCE.indexOf("bar")) },
        )
    }

    fun testDebuggerModuleName() {
        val file = myFixture.configureByText("unlocked.ex", KEYWORD_MODULE)

        assertNotNull(unlocked { breakpointModuleNames(project, file.virtualFile, KEYWORD_MODULE.indexOf("bar")) }?.singleOrNull())
    }

    fun testBreakpointAvailability() {
        val file = myFixture.configureByText("unlocked.ex", KEYWORD_MODULE)
        val type = XDebuggerUtil.getInstance().findBreakpointType(Type::class.java)

        assertTrue(unlocked { type.canPutAt(file.virtualFile, 0, project) })
    }

    fun testElementDescription() {
        val alias = aliasAt("alias A.B, \"as\": C\n", "C")

        assertNotNull(unlocked { ElementDescriptionUtil.getElementDescription(alias, UsageViewTypeLocation.INSTANCE) })
    }

    fun testElementDescriptionOnTheEdt() {
        val alias = aliasAt("alias A.B, \"as\": C\n", "C")

        assertNotNull(onLockFreeEdt { ElementDescriptionUtil.getElementDescription(alias, UsageViewTypeLocation.INSTANCE) })
    }

    fun testQualifiableAliasPresentation() {
        val alias = aliasAt("alias A.B, \"as\": C\n", "C")

        assertEquals("alias A.B, as: C", unlocked { (alias as NavigationItem).presentation!!.presentableText })
    }

    fun test__MODULE__AliasPresentation() {
        val text = "defmodule Foo, other: 1, do: (alias __MODULE__)\n"
        myFixture.configureByText("unlocked.ex", text)
        val call = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(text.indexOf("__MODULE__")), Call::class.java)!!

        assertEquals("alias Foo", unlocked { call.presentation!!.presentableText })
    }

    fun testGotoRelatedSpeedSearchContainerName() {
        val file = myFixture.configureByText("unlocked.ex", SOURCE)
        val definer = PsiTreeUtil.getParentOfType(file.findElementAt(SOURCE.indexOf("def ")), Call::class.java)!!
        val expected = ReadAction.computeBlocking<String?, RuntimeException> { Item(definer).customContainerName }
        // `Provider.getItems` builds the item under a read lock; only the popup's speed search reads it without one.
        val item = ReadAction.computeBlocking<Item, RuntimeException> { Item(definer) }

        assertNotNull(expected)
        assertEquals(expected, unlocked { item.customContainerName })
    }

    fun testRecentLocationsBreadcrumbs() {
        val file = myFixture.configureByText("unlocked.ex", SOURCE)
        val definer = PsiTreeUtil.getParentOfType(file.findElementAt(SOURCE.indexOf("def ")), Call::class.java)!!
        val provider = Provider()

        assertEquals(true to "bar/0", unlocked { provider.acceptElement(definer) to provider.getElementInfo(definer) })
    }

    private fun aliasAt(text: String, word: String): PsiElement {
        myFixture.configureByText("unlocked.ex", text)

        return PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(text.indexOf(word)), QualifiableAlias::class.java)!!
    }

    /** [call] on a pooled thread, as the platform makes it, with no read action. */
    private fun <T> unlocked(call: () -> T): T = AppExecutorUtil.getAppExecutorService().submit(Callable { call() }).get()

    /** [call] on the EDT holding no lock, as a raw Swing event runs. */
    private fun <T> onLockFreeEdt(call: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeLater {
            result = runCatching {
                check(!ApplicationManager.getApplication().isReadAccessAllowed) { "the EDT holds a lock" }
                call()
            }
        }
        // It lets the test's write-intent lock go while it dispatches, so the event runs without one.
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        return result!!.getOrThrow()
    }

    private companion object {
        const val SOURCE = "defmodule Foo do\n  def bar, do: 1\nend\n"

        /** Finding its name reads the keys before `do:`. */
        const val KEYWORD_MODULE = "defmodule Foo, other: 1, do: (def bar, do: 1)\n"
    }
}
