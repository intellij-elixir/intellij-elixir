package org.elixir_lang.ui.code_intelligence

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.client.utility
import com.intellij.driver.sdk.Editor
import com.intellij.driver.sdk.Project
import com.intellij.driver.sdk.TextAttributesKey
import com.intellij.driver.sdk.getHighlights
import com.intellij.driver.sdk.invokeActionByShortcut
import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.components.common.IdeaFrameUI
import com.intellij.driver.sdk.ui.components.common.JEditorUiComponent
import com.intellij.driver.sdk.ui.components.common.codeEditor
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.common.popups.documentationHintEditorPane
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.waitFor
import com.intellij.driver.sdk.waitForCodeAnalysis
import org.elixir_lang.ui.framework.core.IdeTestContext
import java.awt.event.KeyEvent
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** What a completion answered: the lookup's items, or its one item inserted, which closes the lookup. */
internal sealed interface Completed {
    data class Items(val strings: List<String>) : Completed
    data object Inserted : Completed
}

/** Null while the lookup is still filling, which it does in batches. [typed] is `editor`'s text before completing. */
internal fun Lookups.completed(editor: JEditorUiComponent, typed: String): Completed? {
    if (editor.text != typed) return Completed.Inserted
    val lookup = getActiveLookup()?.takeUnless { it.isCalculating() } ?: return null
    return lookup.getItems().map { it.getLookupString() }.takeIf { it.isNotEmpty() }?.let(Completed::Items)
}

/** The gesture never happened, so its row says nothing about the feature. */
private class GestureNotStarted(message: String) : Exception(message)

/** A token, read at `marker`'s offset plus `inset`. */
class Token(val marker: String, val inset: Int)

/**
 * Each probe goes the user's way, by the key the default keymap gives the gesture, and returns what the user would see,
 * as shown. A probe that throws is a reading, so one feature doesn't hide the others.
 */
class FeatureProbes(private val driver: Driver) {
    private fun editor(file: String, marker: String, inset: Int): JEditorUiComponent {
        driver.openFile(file)
        lateinit var editor: JEditorUiComponent
        driver.ideFrame { editor = codeEditor() }
        val opened = editor.editor.getVirtualFile().getPath()
        if (!opened.endsWith(file)) throw GestureNotStarted("$opened is showing, not $file")
        val at = editor.text.indexOf(marker)
        check(at >= 0) { "no '$marker' in $file" }
        editor.click()
        editor.moveCaretToOffset(at + inset)
        val caret = driver.withReadAction { editor.editor.getCaretModel().getOffset() }
        if (caret != at + inset) throw GestureNotStarted("the caret is at $caret, not ${at + inset}, in $file")
        return editor
    }

    private fun frame(block: IdeaFrameUI.() -> Unit) = driver.ideFrame(block)

    /** What's read after it is the answer, so running out of time isn't itself a failure. */
    private fun until(condition: () -> Boolean) {
        runCatching { waitFor("the gesture's effect", WAIT, POLL) { condition() } }
    }

    /**
     * Presses `actionId`'s shortcut in the IDE's keymap, which differs by platform, and sees the platform run it, as a key
     * lost to focus or a disabled action runs nothing.
     */
    private fun started(actionId: String, editor: JEditorUiComponent) {
        val actions = driver.utility(PerformedActions::class).getInstanceEx()
        actions.setLastPreformedActionId(null)
        editor.invokeActionByShortcut(actionId)
        runCatching { waitFor("$actionId to run", START, POLL) { actions.getLastPreformedActionId() == actionId } }.onFailure {
            throw GestureNotStarted("$actionId didn't run; the last action was ${actions.getLastPreformedActionId()}")
        }
    }

    private fun type(editor: JEditorUiComponent, typed: String) {
        editor.keyboard { typeText(typed) }
        runCatching { waitFor("the typed text", START, POLL) { typed.trim() in editor.text } }.onFailure {
            throw GestureNotStarted("'${typed.trim()}' didn't reach the document")
        }
    }

    private fun probe(gesture: () -> String): Reading {
        val reading = runCatching(gesture).fold(
            { Reading.Read(it) },
            { if (it is GestureNotStarted) Reading.NotStarted(it.message.orEmpty()) else Reading.Read("${it::class.simpleName}: ${it.message}") },
        )
        runCatching { frame { keyboard { key(KeyEvent.VK_ESCAPE) } } }
        return reading
    }

    /** The open popup's text, or none: `present` first, as `getAllTexts` waits its own 15 s for a popup that isn't there. */
    private fun popupText(): String {
        var text = ""
        frame {
            val popup = x("//div[@class='HeavyWeightWindow']")
            if (popup.present()) text = popup.getAllTexts().joinToString("") { it.text }
        }
        return text
    }

    /** The lookup's strings, one per line, or, when its one item was inserted, the word then at the caret. */
    fun completion(file: String, marker: String, typed: String): Reading = probe {
        val editor = editor(file, marker, marker.length)
        val original = editor.text
        try {
            type(editor, typed)
            val typedText = editor.text
            started("CodeCompletion", editor)
            val lookups = driver.utility(Lookups::class).getInstance(driver.singleProject())
            var answer: Completed? = null
            until {
                answer = lookups.completed(editor, typedText)
                answer != null
            }
            editor.keyboard { key(KeyEvent.VK_ESCAPE) }
            when (val completed = answer) {
                Completed.Inserted -> wordAtCaret(editor)
                is Completed.Items -> completed.strings.distinct().joinToString("\n")
                null -> ""
            }
        } finally {
            editor.text = original
        }
    }

    private fun wordAtCaret(editor: JEditorUiComponent): String {
        val text = editor.text
        val caret = driver.withReadAction { editor.editor.getCaretModel().getOffset() }
        val word = Regex("[\\w?!]")
        var start = caret
        while (start > 0 && word.matches(text[start - 1].toString())) start--
        return text.substring(start, caret)
    }

    /** The open editor's file, shown relative to the subject or the Elixir SDK, and the trimmed text of the caret's line. */
    private fun location(): String {
        lateinit var at: String
        frame {
            codeEditor().let { editor ->
                val path = editor.editor.getVirtualFile().getPath()
                val line = editor.getCaretLine()
                val text = editor.text.lines().getOrElse(line - 1) { "" }.trim()
                at = "${shown(path)}: $text"
            }
        }
        return at
    }

    private fun shown(path: String): String {
        // A virtual file's path separates with `/` on every platform.
        val roots = listOf(Subject.root, java.nio.file.Path.of(IdeTestContext.elixirSdkPath).normalize()).map { it.invariantSeparatorsPathString }
        return roots.firstNotNullOfOrNull { root -> path.removePrefix("$root/").takeIf { it != path } } ?: path
    }

    /** Path and line are two calls, and a new editor can answer the second before the first. */
    private fun settledLocation(): String {
        Thread.sleep(SETTLE.inWholeMilliseconds)
        return location()
    }

    /** More than one target opens a chooser and leaves the caret where it was: the chooser is then the reading. */
    fun navigation(file: String, marker: String, inset: Int): Reading = probe {
        val editor = editor(file, marker, inset)
        val before = location()
        started("GotoDeclaration", editor)
        var chooser = ""
        until {
            chooser = runCatching { popupText() }.getOrDefault("")
            chooser.isNotEmpty() || location() != before
        }
        when {
            chooser.isNotEmpty() -> "chooser: $chooser"
            location() == before -> "unmoved"
            else -> settledLocation()
        }
    }

    /**
     * The popup's text as shown, from its document: the pane's `getText` is the HTML. The zero-width spaces its `<wbr>`s
     * leave, between a module's segments, aren't seen.
     */
    fun quickDoc(file: String, marker: String, inset: Int): Reading = probe {
        val editor = editor(file, marker, inset)
        started("QuickJavaDoc", editor)
        var doc = ""
        frame {
            documentationHintEditorPane {
                val document = driver.cast(component, TextPane::class).getDocument()
                doc = document.getText(0, document.getLength()).replace("\u200B", "")
            }
        }
        doc
    }

    /** The SDK has no helper for this popup, so its class is matched by name. The plugin writes its own separators. */
    fun parameterInfo(file: String, marker: String, inset: Int): Reading = probe {
        val editor = editor(file, marker, inset)
        started("ParameterInfo", editor)
        var hint = "no popup"
        until {
            frame {
                val popup = x { byClass("ParameterInfoComponent") }
                if (popup.present()) hint = popup.getAllTexts().joinToString("") { it.text }
            }
            hint != "no popup"
        }
        hint
    }

    /**
     * The plugin's keys whose attributes the highlight over each token carries, one token per line. The annotators
     * resolve their key and keep only its attributes, so a highlight is named by the keys that resolve to them.
     */
    fun highlighting(file: String, tokens: List<Token>): Reading = probe {
        val editor = editor(file, "", 0)
        driver.waitForCodeAnalysis(file = editor.editor.getVirtualFile())
        val text = editor.text
        val highlights = driver.getHighlights(editor.document)
        // A `Ref`'s `toString` is the IDE side's, and a field has no driver call.
        val scheme = driver.utility(ColorsManagers::class).getInstance().getGlobalScheme()
        val keysOf = PLUGIN_KEYS.groupBy { scheme.getAttributes(driver.utility(TextAttributesKeys::class).find(it)).toString() }
        val ranges = highlights.mapNotNull { info ->
            info.getHighlighter()?.let { highlighter ->
                val attributes = info.toString().substringAfter("forcedTextAttributes: ", "").substringBefore(';')
                Triple(highlighter.getStartOffset(), highlighter.getEndOffset(), keysOf[attributes].orEmpty())
            }
        }
        tokens.joinToString("\n") { token ->
            val at = text.indexOf(token.marker)
            check(at >= 0) { "no '${token.marker}' in $file" }
            val offset = at + token.inset
            val keys = ranges.filter { offset >= it.first && offset < it.second }.flatMap { it.third }.distinct().sorted()
            "${token.marker}: ${keys.joinToString(" ")}"
        }
    }

    /** A single usage found before the table opens is jumped to, with a hint in place of the table, so the move is read first. */
    fun findUsages(file: String, marker: String, inset: Int): Reading = probe {
        val editor = editor(file, marker, inset)
        val before = location()
        started("ShowUsages", editor)
        var table = ""
        until {
            table = runCatching { popupText() }.getOrDefault("")
            table.isNotEmpty() || location() != before
        }
        Thread.sleep(SETTLE.inWholeMilliseconds)
        when {
            location() != before -> settledLocation()
            table.isNotEmpty() -> searched().let { if (it.isEmpty()) "the table closed" else "table: $it" }
            else -> "unmoved, no table"
        }
    }

    /** The usages table once its search ends: until then its header says "Searching". */
    private fun searched(): String {
        var table = ""
        until {
            table = runCatching { popupText() }.getOrDefault("")
            "Searching" !in table
        }
        return table
    }

    /** The lines holding `newName` once renamed, of a name local to one file, so restoring that document undoes it. */
    fun rename(file: String, marker: String, newName: String): Reading = probe {
        val editor = editor(file, marker, 1)
        val original = editor.text
        try {
            started("RenameElement", editor)
            val templates = driver.utility(TemplateStates::class)
            runCatching {
                waitFor("a rename to open", START, POLL) {
                    templates.getTemplateState(editor.editor) != null || driver.ui.x(RENAME_DIALOG).present()
                }
            }.onFailure {
                throw GestureNotStarted("RenameElement ran and opened no rename: ${popupText()}")
            }
            editor.keyboard {
                typeText(newName)
                key(KeyEvent.VK_ENTER)
            }
            until { marker !in editor.text }
            editor.text.lines().filter { marker in it || newName in it }.joinToString("\n") { it.trim() }
        } finally {
            editor.text = original
        }
    }

    /** The line typed into, as the document holds it. */
    fun typing(file: String, marker: String, typed: String): Reading = probe {
        val editor = editor(file, marker, marker.length)
        val original = editor.text
        try {
            type(editor, typed)
            editor.text.lines().filter { typed.trim() in it }.joinToString("\n")
        } finally {
            editor.text = original
        }
    }

    private companion object {
        val WAIT = 15.seconds
        val START = 5.seconds
        val POLL = 100.milliseconds
        val SETTLE = 1.seconds
        const val RENAME_DIALOG = "//div[@class='MyDialog' and contains(@title, 'Rename')]"
        val PLUGIN_KEYS = listOf(
            "ELIXIR_ALIAS",
            "ELIXIR_FUNCTION_CALL",
            "ELIXIR_FUNCTION_DECLARATION",
            "ELIXIR_MACRO_CALL",
            "ELIXIR_MODULE_ATTRIBUTE",
            "ELIXIR_PARAMETER",
            "ELIXIR_PREDEFINED",
            "ELIXIR_VARIABLE",
        )
    }
}

@Remote("javax.swing.text.JTextComponent")
internal interface TextPane {
    fun getDocument(): PaneDocument
}

@Remote("javax.swing.text.Document")
internal interface PaneDocument {
    fun getLength(): Int
    fun getText(offset: Int, length: Int): String
}

@Remote("com.intellij.openapi.editor.colors.TextAttributesKey")
internal interface TextAttributesKeys {
    fun find(externalName: String): TextAttributesKey
}

@Remote("com.intellij.openapi.editor.colors.EditorColorsManager")
internal interface ColorsManagers {
    fun getInstance(): ColorsManagers
    fun getGlobalScheme(): ColorsScheme
}

@Remote("com.intellij.openapi.editor.colors.EditorColorsScheme")
internal interface ColorsScheme {
    fun getAttributes(key: TextAttributesKey): ResolvedAttributes
}

@Remote("com.intellij.openapi.editor.markup.TextAttributes")
internal interface ResolvedAttributes

@Remote("com.intellij.openapi.actionSystem.impl.ActionManagerImpl")
internal interface PerformedActions {
    fun getInstanceEx(): PerformedActions
    fun getLastPreformedActionId(): String?
    fun setLastPreformedActionId(id: String?)
}

@Remote("com.intellij.codeInsight.template.impl.TemplateManagerImpl")
internal interface TemplateStates {
    fun getTemplateState(editor: Editor): TemplateStateRef?
}

@Remote("com.intellij.codeInsight.template.impl.TemplateState")
internal interface TemplateStateRef

@Remote("com.intellij.codeInsight.lookup.LookupManager")
internal interface Lookups {
    fun getInstance(project: Project): Lookups
    fun getActiveLookup(): ActiveLookup?
}

@Remote(serviceInterface = "com.intellij.codeInsight.lookup.Lookup", value = "com.intellij.codeInsight.lookup.impl.LookupImpl")
internal interface ActiveLookup {
    fun getItems(): List<NamedLookupElement>
    fun isCalculating(): Boolean
}

@Remote("com.intellij.codeInsight.lookup.LookupElement")
internal interface NamedLookupElement {
    fun getLookupString(): String
}
