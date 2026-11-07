package org.elixir_lang.sdk

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ProjectViewNodeDecorator
import com.intellij.ide.projectView.impl.nodes.PsiDirectoryNode
import com.intellij.openapi.components.service

/**
 * Renames SDK library root nodes in External Libraries from the generic directory name - "ebin",
 * "lib" or "src" - to the parent application name (e.g., "iex", "elixir", "logger", "stdlib-7.1").
 *
 * Without this, every Elixir/Erlang SDK entry in External Libraries is labeled "ebin library root",
 * making it impossible to tell which OTP application each node belongs to.
 *
 * Works in tandem with [ElixirSdkLibraryTreeStructureProvider], which replaces an "ebin" class root
 * node with its sibling source root: "lib" for an application written in Elixir, "src" for one
 * written in Erlang.
 */
internal class ElixirSdkLibraryNodeDecorator : ProjectViewNodeDecorator {
    override fun decorate(node: ProjectViewNode<*>, data: PresentationData) {
        if (node !is PsiDirectoryNode) return
        val vFile = node.value?.virtualFile ?: return

        // Cheap name pre-filter before touching the cache
        if (vFile.name !in ROOT_DIRECTORY_NAMES) return

        // O(1) set lookup - avoids scanning all SDKs on every render
        if (vFile !in service<ElixirSdkRootsCache>().classAndSourceRoots()) return

        // Only a root standing on its own needs the application's name. Where
        // ElixirSdkLibraryTreeStructureProvider has grouped an application's roots under one node,
        // that node already carries the application name and its children have to keep `ebin` and
        // `src`/`lib` to be told apart.
        if (node.parent?.let { it !is PsiDirectoryNode && it.value is String } == true) return

        // Rename to the OTP application directory name, e.g. lib/iex/ebin -> "iex"
        data.presentableText = vFile.parent?.name ?: return
    }
}

/**
 * The generic directory names an SDK root can end in: beams, Elixir source, Erlang source.
 * All three are named after their content rather than their application, so all three need
 * the parent's name to be legible in the tree.
 */
private val ROOT_DIRECTORY_NAMES = setOf("ebin", "lib", "src")

