package org.elixir_lang.sdk.choice

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.replaceService
import com.intellij.util.ui.EDT
import junit.framework.TestCase.assertEquals
import org.elixir_lang.jps.shared.tool_manager.ToolManagerEnvironmentResolver
import org.elixir_lang.sdk.erlang_dependent.elixirInstall
import org.elixir_lang.sdk.erlang_dependent.install
import org.elixir_lang.tool_manager.BuiltInToolManagers
import org.elixir_lang.tool_manager.ElixirToolManager
import org.elixir_lang.tool_manager.ToolEntry
import org.elixir_lang.tool_manager.ToolManagerEnvironments
import org.elixir_lang.tool_manager.ToolManagerResult
import org.elixir_lang.tool_manager.mise.MiseToolManager
import org.elixir_lang.tool_manager.versions
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A tool manager whose answer per folder is [answer], recording what it was asked and where from. */
internal class FakeToolManager(override val name: String = MiseToolManager.NAME) : ElixirToolManager {
    override val displayName = "mise"

    @Volatile
    var answer: (Path) -> ToolManagerResult? = { null }
    val asked: MutableList<Path> = mutableListOf()
    private val badThreads = mutableListOf<String>()

    override fun resolveVersions(contentRoot: Path): ToolManagerResult? {
        if (EDT.isCurrentThreadEdt() || ApplicationManager.getApplication().holdsReadLock()) {
            synchronized(badThreads) { badThreads.add(Thread.currentThread().name) }
        }
        synchronized(asked) { asked.add(contentRoot) }
        return answer(contentRoot)
    }

    /** Answers that this manager pins the Elixir at [elixirHome], and the Erlang at [erlangHome] unless it is null, in every folder. */
    fun pins(elixirHome: String, erlangHome: String?) {
        val answer = success(elixirHome, erlangHome)
        this.answer = { answer }
    }

    /** The pins [OFFER] names, at installs made for them. */
    fun offeredPins(): ToolManagerResult = success(elixirInstall("1.18.4", "27"), install("27", "27.3.4"))

    /** Answers [offeredPins] in every folder. */
    fun pinsTheOffer() {
        val answer = offeredPins()
        this.answer = { answer }
    }

    /** Answers [offeredPins] in every folder once the latch this returns is released. */
    fun holdsTheOffer(): CountDownLatch {
        val answer = offeredPins()
        val release = CountDownLatch(1)
        this.answer = {
            release.await(10, TimeUnit.SECONDS)
            answer
        }
        return release
    }

    /** Answers [pins]' pins everywhere but [folder], which pins another Elixir, as [FOLDER_OFFER] names it. */
    fun pinsAnotherIn(folder: Path) {
        val erlang = install("27", "27.3.4")
        val there = success(ToolEntry("1.17.3-otp-27", elixirInstall("1.17.3", "27"), true), ToolEntry("27.3.4", erlang, true))
        val elsewhere = success(elixirInstall("1.18.4", "27"), erlang)
        answer = { if (it == folder) there else elsewhere }
    }

    /** Pins of the Elixir at [elixirHome] as [OFFER] names it, and of the Erlang at [erlangHome] unless it is null. */
    fun success(elixirHome: String, erlangHome: String?): ToolManagerResult =
        success(ToolEntry("1.18.4-otp-27", elixirHome, true), erlangHome?.let { ToolEntry("27.3.4", it, true) })

    fun success(elixir: ToolEntry, erlang: ToolEntry?): ToolManagerResult =
        ToolManagerResult.Success(versions(toolManagerName = name, elixir = elixir, erlang = erlang))

    /** Until [disposable] goes, the only built-in tool manager. */
    fun install(disposable: Disposable): FakeToolManager = also {
        ApplicationManager.getApplication().replaceService(BuiltInToolManagers::class.java, BuiltInToolManagers(listOf(it)), disposable)
    }

    fun assertNeverOnTheEdtOrUnderAReadLock() = assertEquals(emptyList<String>(), badThreads)
}

/** The chooser's texts for the pins [FakeToolManager.pins] answers. */
internal const val OFFER = "Use mise's Elixir 1.18.4-otp-27 and Erlang 27.3.4"
internal const val FOLDER_OFFER = "Use mise's Elixir 1.17.3-otp-27 and Erlang 27.3.4"
internal const val ELIXIR_ONLY_OFFER = "Use mise's Elixir 1.18.4-otp-27"
internal const val IN_STEP = "Keep the SDKs in step with mise"
internal const val ENVIRONMENT = "Use mise's environment for Mix and the build"

/** Until [disposable] goes, the environment of each tool manager that applies one, by name. */
internal fun replaceToolManagerEnvironments(disposable: Disposable, resolvers: Map<String, ToolManagerEnvironmentResolver>) {
    ApplicationManager.getApplication().replaceService(ToolManagerEnvironments::class.java, ToolManagerEnvironments(resolvers), disposable)
}

/** The variables a test's tool manager environment sets, and the line `env` prints for them. */
internal val TOOL_MANAGER_PROBE = mapOf("TOOL_MANAGER_PROBE" to "from-tool-manager")
internal const val TOOL_MANAGER_PROBE_LINE = "TOOL_MANAGER_PROBE=from-tool-manager"
