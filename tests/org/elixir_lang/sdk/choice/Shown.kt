package org.elixir_lang.sdk.choice

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.util.concurrent.TimeUnit
import javax.swing.AbstractButton
import javax.swing.JComponent
import javax.swing.JLabel

/** The button or box under this component whose text is [text], once it shows. */
internal fun JComponent.waitFor(text: String): AbstractButton = waitFor(text) { it == text } as AbstractButton

/** The component under this one whose text [matches], once it shows, failing the test as "not found: [what]". */
internal fun JComponent.waitFor(what: String, matches: (String) -> Boolean): JComponent {
    var found: JComponent? = null
    PlatformTestUtil.waitWithEventsDispatching("not found: $what", { shown(matches)?.also { found = it } != null }, 10)
    return found!!
}

/** Dispatches the EDT's events for half a second, long enough for an answer already on its way to arrive. */
internal fun dispatchForAWhile() {
    val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500)
    while (System.nanoTime() < until) {
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        Thread.sleep(20)
    }
}

/** The component under this one whose text [matches], when it and everything holding it under this one is visible. */
internal fun JComponent.shown(matches: (String) -> Boolean): JComponent? {
    val root = this
    return UIUtil.uiTraverser(root).filter(JComponent::class.java).find { component ->
        val text = when (component) {
            is AbstractButton -> component.text
            is JLabel -> component.text
            else -> null
        }
        text != null && matches(text) && generateSequence<Component>(component) { it.parent }.takeWhile { it !== root }.all { it.isVisible }
    }
}
