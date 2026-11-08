package org.elixir_lang.sdk.choice

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.PlatformTestUtil
import org.elixir_lang.junit.HeavyTestCase
import org.elixir_lang.tool_manager.ToolEntry
import org.elixir_lang.tool_manager.ToolManagerResult
import org.elixir_lang.tool_manager.ToolManagerSettings
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.JPanel

/** What the chooser hears from the tool managers about its folders while none of them is enabled. */
class ToolManagerOfferTest : HeavyTestCase() {
    private val fake = FakeToolManager("fake")
    private val elixir = ToolEntry("1.20.4-otp-29", "/mise/installs/elixir/1.20.4-otp-29", true)
    private val erlang = ToolEntry("29.0.6", "/mise/installs/erlang/29.0.6", true)
    private val other = ToolEntry("1.19.5-otp-28", "/mise/installs/elixir/1.19.5-otp-28", true)

    override fun setUp() {
        super.setUp()
        fake.install(testRootDisposable)
    }

    fun testWithTheToolManagerOffThePinsAreFound() {
        assertFalse(ToolManagerSettings.getInstance(project).isEnabled(fake))
        fake.answer = { fake.success(elixir, erlang) }

        val offer = check(folder("a")) as? ToolManagerCheck.Offer ?: throw AssertionError("an offer")

        assertEquals(listOf(fake.name, elixir, erlang), offer.versions.let { listOf(it.toolManagerName, it.elixir, it.erlang) })
        fake.assertNeverOnTheEdtOrUnderAReadLock()
    }

    fun testAnUntrustedConfigIsReported() {
        fake.answer = { ToolManagerResult.Error(fake.name, "<b>untrusted</b>") }

        assertEquals(ToolManagerCheck.Error("<b>untrusted</b>"), check(folder("a")))
    }

    fun testAnElixirPinWithNoErlangPinIsFound() {
        fake.answer = { fake.success(elixir, null) }

        assertTrue(check(folder("a")) is ToolManagerCheck.Offer)
    }

    fun testEveryFolderIsAsked() {
        fake.answer = { fake.success(elixir, erlang) }

        check(folder("a"), folder("b"))

        assertEquals(listOf(folder("a"), folder("b")), fake.asked)
    }

    fun testPinsDifferingBetweenFoldersOfferNothing() {
        fake.answer = { folder ->
            fake.success(if (folder == folder("a")) elixir else other, erlang)
        }

        assertNull(check(folder("a"), folder("b")))
    }

    fun testAnElixirPinNotInstalledOffersNothing() {
        fake.answer = { fake.success(elixir.copy(installed = false), erlang) }

        assertNull(check(folder("a")))
    }

    fun testAnErlangPinNotInstalledOffersNothing() {
        fake.answer = { fake.success(elixir, erlang.copy(installed = false)) }

        assertNull(check(folder("a")))
    }

    fun testNoManagerAnsweringOffersNothing() {
        fake.answer = { null }

        assertNull(check(folder("a")))
        fake.assertNeverOnTheEdtOrUnderAReadLock()
    }

    fun testAFolderNotMadeYetIsAskedAboutTheFolderItGoesIn() {
        fake.answer = { fake.success(elixir, erlang) }

        check(folder("a").resolve("new/project"), folder("a").resolve("other"))

        assertEquals(listOf(folder("a")), fake.asked)
    }

    fun testACheckStillRunningIsDroppedForTheNextOne() {
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        fake.answer = { folder ->
            if (folder == folder("a")) {
                release.await(10, TimeUnit.SECONDS)
                fake.success(elixir, erlang).also { returned.countDown() }
            } else {
                fake.success(other, erlang)
            }
        }
        val state = state(folder("a"))
        val answers = mutableListOf<ToolManagerCheck?>()
        try {
            state.check(JPanel()) { answers += it }
            PlatformTestUtil.waitWithEventsDispatching("the manager is asked", { fake.asked.isNotEmpty() }, 10)
            state.retarget(null, listOf(folder("b")))
            state.check(JPanel()) { answers += it }
            PlatformTestUtil.waitWithEventsDispatching("the check answers", { answers.isNotEmpty() }, 10)
        } finally {
            release.countDown()
        }

        assertTrue(returned.await(10, TimeUnit.SECONDS))
        dispatchForAWhile()
        assertEquals(listOf(other), answers.map { (it as ToolManagerCheck.Offer).elixir })
    }

    fun testACheckRunningWhenTheStateGoesIsDropped() {
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        fake.answer = {
            release.await(10, TimeUnit.SECONDS)
            fake.success(elixir, erlang).also { returned.countDown() }
        }
        val state = state(folder("a"))
        val answers = mutableListOf<ToolManagerCheck?>()
        try {
            state.check(JPanel()) { answers += it }
            PlatformTestUtil.waitWithEventsDispatching("the manager is asked", { fake.asked.isNotEmpty() }, 10)
            Disposer.dispose(state)
        } finally {
            release.countDown()
        }

        assertTrue(returned.await(10, TimeUnit.SECONDS))
        dispatchForAWhile()
        assertEquals(emptyList<ToolManagerCheck?>(), answers)
    }

    private val root: Path by lazy { FileUtil.createTempDirectory("tool-manager-offer", null, true).toPath() }

    private fun folder(name: String): Path = Files.createDirectories(root.resolve(name))

    private fun state(vararg folders: Path): SdkChoiceState =
        SdkChoiceState(project, null, null, folders.toList(), hostCommits = true).also { Disposer.register(testRootDisposable, it) }

    /** The answer a state over [folders] hears, once it comes. */
    private fun check(vararg folders: Path): ToolManagerCheck? {
        val state = state(*folders)
        val answers = mutableListOf<ToolManagerCheck?>()
        state.check(JPanel()) { answers += it }
        PlatformTestUtil.waitWithEventsDispatching("the check answers", { answers.isNotEmpty() }, 10)
        return answers.single()
    }
}
