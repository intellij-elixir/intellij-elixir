package org.elixir_lang.sdk.choice

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.replaceService
import com.intellij.util.ui.EDT
import junit.framework.TestCase.assertEquals
import org.elixir_lang.tool_manager.BuiltInToolManagers
import org.elixir_lang.tool_manager.ElixirToolManager
import org.elixir_lang.tool_manager.ToolEntry
import org.elixir_lang.tool_manager.ToolManagerResult
import org.elixir_lang.tool_manager.mise.MiseToolManager
import org.elixir_lang.tool_manager.versions
import java.nio.file.Path

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

    fun success(elixir: ToolEntry, erlang: ToolEntry?): ToolManagerResult =
        ToolManagerResult.Success(versions(toolManagerName = name, elixir = elixir, erlang = erlang))

    /** Until [disposable] goes, the only built-in tool manager. */
    fun install(disposable: Disposable): FakeToolManager = also {
        ApplicationManager.getApplication().replaceService(BuiltInToolManagers::class.java, BuiltInToolManagers(listOf(it)), disposable)
    }

    fun assertNeverOnTheEdtOrUnderAReadLock() = assertEquals(emptyList<String>(), badThreads)
}
