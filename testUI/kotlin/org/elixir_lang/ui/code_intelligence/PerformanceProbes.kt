package org.elixir_lang.ui.code_intelligence

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.client.utility
import com.intellij.driver.sdk.VirtualFile
import com.intellij.driver.sdk.WaitForException
import com.intellij.driver.sdk.findFile
import com.intellij.driver.sdk.invokeActionByShortcut
import com.intellij.driver.sdk.isCodeAnalysisFinished
import com.intellij.driver.sdk.openEditor
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.components.common.JEditorUiComponent
import com.intellij.driver.sdk.ui.components.common.codeEditor
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.waitFor
import java.awt.event.KeyEvent
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.Path
import kotlin.io.path.appendText
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * One run's recordings, in their own directory under `build/`: `figures.tsv`, `spans.json` and `flight.jfr`. A figure is
 * judged only for a hang, a wait that ran out its timeout, which [hangs] lists.
 */
class Recording(run: String) {
    private val directory: Path = Path(System.getProperty("testUi.recordings") ?: error("testUi.recordings is not set: run through the testUI task"))
        .resolve("$run-${Instant.now().toString().replace(':', '-')}")
        .createDirectories()
    val flight: Path = directory.resolve("flight.jfr")
    private val figures = directory.resolve("figures.tsv").apply { writeText("figure\tvalue\tunit\toutcome\n") }
    private val hung = mutableListOf<String>()
    val hangs: List<String> get() = hung

    fun figure(name: String, value: Long, unit: String) = figures.appendText("$name\t$value\t$unit\tmeasured\n")

    /** Times [gesture], and says whether it finished. A hang is recorded with the time it ran, and the run goes on. */
    fun timed(name: String, gesture: () -> Unit): Boolean {
        val start = TimeSource.Monotonic.markNow()
        return try {
            gesture()
            figure(name, start.elapsedNow().inWholeMilliseconds, "ms")
            true
        } catch (hang: WaitForException) {
            figures.appendText("$name\t${start.elapsedNow().inWholeMilliseconds}\tms\thang\n")
            hung += "$name: ${hang.message}"
            false
        }
    }

    /** The IDE's spans, written to its log directory as it exits. */
    fun spans(testHome: Path) {
        testHome.resolve("log/opentelemetry.json").copyTo(directory.resolve("spans.json"))
    }
}

/**
 * First highlight, five completions, typing and a file created, each timed as the user's whole gesture, then memory after
 * a gc. Run first, so the first highlight is the file's first. Analysis is polled at 50 ms, where the driver's own wait
 * polls each second.
 */
class PerformanceProbes(private val driver: Driver, private val recording: Recording) {
    fun run(file: String, marker: String) {
        firstHighlight(file)
        val editor = editorAt(file, marker)
        completions(editor, marker)
        typing(editor, marker)
        fileCreated()
        memory()
    }

    private fun analysed(file: VirtualFile) =
        waitFor("analysis of ${file.getName()}", ANALYSIS, POLL) { driver.isCodeAnalysisFinished(file = file) }

    private fun firstHighlight(file: String) = recording.timed("first highlight") {
        val virtualFile = checkNotNull(driver.findFile(file)) { "no $file in ${Subject.name}" }
        driver.openEditor(virtualFile)
        analysed(virtualFile)
    }

    private fun editorAt(file: String, marker: String): JEditorUiComponent {
        lateinit var editor: JEditorUiComponent
        driver.ideFrame { editor = codeEditor() }
        check(editor.editor.getVirtualFile().getPath().endsWith(file)) { "$file isn't the editor showing" }
        check(marker in editor.text) { "no '$marker' in $file" }
        return editor
    }

    private fun caretAfter(editor: JEditorUiComponent, marker: String) {
        editor.click()
        editor.moveCaretToOffset(editor.text.indexOf(marker) + marker.length)
    }

    /** The first is the launch's first in the file; the rest show what warming does. */
    private fun completions(editor: JEditorUiComponent, marker: String) {
        val original = editor.text
        val lookups = driver.utility(Lookups::class).getInstance(driver.singleProject())
        caretAfter(editor, marker)
        try {
            // On a line of its own, as the line after the marker has code on it.
            editor.keyboard { typeText("$COMPLETED\n") }
            waitFor("the typed text", TEXT, POLL) { COMPLETED in editor.text }
            val typed = editor.text
            val caret = typed.indexOf(COMPLETED) + COMPLETED.length
            editor.moveCaretToOffset(caret)
            repeat(COMPLETIONS) { index ->
                recording.timed("completion ${index + 1}") {
                    editor.invokeActionByShortcut("CodeCompletion")
                    waitFor("the lookup", LOOKUP, POLL) { lookups.completed(editor, typed) != null }
                }
                editor.keyboard { key(KeyEvent.VK_ESCAPE) }
                editor.text = typed
                editor.moveCaretToOffset(caret)
            }
        } finally {
            editor.text = original
        }
    }

    private fun typing(editor: JEditorUiComponent, marker: String) {
        val original = editor.text
        caretAfter(editor, marker)
        try {
            recording.timed("typing") {
                editor.keyboard { typeText(TYPED) }
                waitFor("the typed text", TEXT, POLL) { TYPED.trim() in editor.text }
                analysed(editor.editor.getVirtualFile())
            }
        } finally {
            editor.text = original
        }
    }

    /** Written from outside, so refreshed by path: the IDE doesn't find it unprompted within a minute. */
    private fun fileCreated() {
        val path = Subject.root.resolve("lib/own/created.ex")
        check(!path.exists()) { "$path exists: the file-created probe writes and deletes it, so move it out of the subject" }
        try {
            recording.timed("file created") {
                path.writeText("defmodule Own.Created do\n  def shout(text), do: String.upcase(text)\nend\n")
                val file = checkNotNull(driver.utility(Filesystem::class).getInstance().refreshAndFindFileByPath(path.toString()))
                driver.openEditor(file)
                analysed(file)
            }
        } finally {
            path.deleteIfExists()
        }
    }

    private fun memory() {
        val runtime = driver.utility(JvmRuntime::class).getRuntime()
        runtime.gc()
        recording.figure("memory after gc", runtime.totalMemory() - runtime.freeMemory(), "bytes")
    }

    private companion object {
        const val COMPLETIONS = 5
        const val COMPLETED = "    String.spl"
        const val TYPED = "    probe_value = 1 + 2\n"
        val POLL = 50.milliseconds
        val TEXT = 30.seconds
        val LOOKUP = 30.seconds
        val ANALYSIS = 2.minutes
    }
}

@Remote("com.intellij.openapi.vfs.LocalFileSystem")
private interface Filesystem {
    fun getInstance(): Filesystem
    fun refreshAndFindFileByPath(path: String): VirtualFile?
}

@Remote("java.lang.Runtime")
private interface JvmRuntime {
    fun getRuntime(): JvmRuntime
    fun gc()
    fun totalMemory(): Long
    fun freeMemory(): Long
}
