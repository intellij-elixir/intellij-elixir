package org.elixir_lang.structure_view

import com.intellij.ide.structureView.StructureViewTreeElement
import org.elixir_lang.psi.ElixirFile

/** The [T] elements [file]'s structure view shows, in tree order. */
internal inline fun <reified T : StructureViewTreeElement> structureViewElements(file: ElixirFile): List<T> =
    allStructureViewElements(file).filterIsInstance<T>()

/** Every element [file]'s structure view shows, in tree order. */
internal fun allStructureViewElements(file: ElixirFile): List<StructureViewTreeElement> {
    val found = mutableListOf<StructureViewTreeElement>()

    fun walk(element: StructureViewTreeElement) {
        found.add(element)

        for (child in element.children) {
            if (child is StructureViewTreeElement) walk(child)
        }
    }

    walk(Model(file, null).root)

    return found
}
