package org.elixir_lang.sdk

import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.TreeStructureProvider
import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.projectView.impl.nodes.ExternalLibrariesNode
import com.intellij.ide.projectView.impl.nodes.NamedLibraryElementNode
import com.intellij.ide.projectView.impl.nodes.PsiDirectoryNode
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.JdkOrderEntry
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import org.elixir_lang.jps.shared.ElixirSdkTypeId
import org.elixir_lang.jps.shared.ErlangSdkTypeId
import org.elixir_lang.mix.buildEnv
import org.elixir_lang.mix.sync.isConsolidatedLibraryName
import org.elixir_lang.mix.sync.scopedLibraryNameToken

/**
 * Lifts an Elixir/Erlang SDK "ebin" class root node in External Libraries to the application
 * directory that holds it, so the beams and the source beside them are both reachable.
 *
 * An OTP application keeps its source beside its beams, under a name that depends on the language it
 * is written in:
 * ```
 * lib/<app>/ebin/         ← beam files (CLASSES root)
 * lib/<app>/lib/          ← .ex source, for an application written in Elixir (SOURCES root)
 * lib/<app>-<version>/src ← .erl source, for one written in Erlang (SOURCES root)
 * ```
 *
 * Without this provider, External Libraries lists each root separately, every one of them labelled
 * by its own generic directory name. With it, an application that has both appears once, and
 * expanding it gives `ebin` and `lib`/`src`.
 *
 * An application shipping only beams has no source sibling, so there is nothing to choose between
 * and that level is skipped: its `ebin` node stands as it is, named after the application by
 * [ElixirSdkLibraryNodeDecorator].
 *
 * Works in tandem with [ElixirSdkLibraryNodeDecorator] which renames the resulting "lib" (and
 * remaining "ebin") nodes from their generic directory name to the parent application name.
 *
 * When a Mix dependency has roots compiled for multiple environments (e.g. `dev` and `test` both
 * present under `_build/`), the duplicate `ebin` and `consolidated` roots are grouped under virtual
 * subdirectory nodes named after the build environment so they can be distinguished.
 */
internal class ElixirSdkLibraryTreeStructureProvider : TreeStructureProvider, DumbAware {
    override fun modify(
        parent: AbstractTreeNode<*>,
        children: Collection<AbstractTreeNode<*>>,
        settings: ViewSettings,
    ): Collection<AbstractTreeNode<*>> {
        // Give nodes explicit weights so IntelliJ's tree sorter produces a stable order:
        //   Elixir/Erlang SDKs (weight -2) -> consolidated (weight -1) -> all other deps (weight 0, alphabetical)
        //
        // This provider is registered application-wide (the treeStructureProvider extension point has no
        // per-project filter), so scope the transformations to Elixir/Erlang content only: guard SDK nodes by
        // their SDK type and consolidated nodes by the plugin-specific library name. Otherwise SDKs in
        // unrelated (e.g. Java) projects would be re-weighted too.
        if (parent is ExternalLibrariesNode) {
            return children.map { node ->
                if (node !is NamedLibraryElementNode) return@map node
                when (val entry = node.value?.orderEntry) {
                    is JdkOrderEntry ->
                        if (entry.isElixirOrErlangSdk()) SdkLibraryNode(node, settings) else node
                    is LibraryOrderEntry -> {
                        val libName = entry.libraryName
                        when {
                            libName != null && isConsolidatedLibraryName(libName) ->
                                ConsolidatedLibraryNode(node, settings)
                            libName != null && scopedLibraryNameToken(libName) != null ->
                                ScopedDepLibraryNode(node, settings)
                            else -> node
                        }
                    }
                    else -> node
                }
            }
        }

        // SDK ebin dirs are direct children of NamedLibraryElementNode; skip everything else cheaply.
        if (parent !is NamedLibraryElementNode) return children
        val project = parent.project ?: return children

        // Pair each SDK application's beams with its source first, so the grouped node gets the
        // right env detected below.
        val processed = children.map { child ->
            tryGroupEbinWithSource(child, project, settings) ?: child
        }

        // Group Mix build roots by their _build environment (e.g. "dev", "test").
        // Roots not under _build (e.g. a deps/ SOURCES root) go in the null bucket.
        val byEnv = LinkedHashMap<String?, MutableList<AbstractTreeNode<*>>>()
        for (node in processed) {
            val vFile = (node as? PsiDirectoryNode)?.value?.virtualFile
            byEnv.getOrPut(buildEnv(vFile)) { mutableListOf() }.add(node)
        }

        val envKeys = byEnv.keys.filterNotNull()
        if (envKeys.size < 2) return if (processed == children) children else processed

        // Emit non-_build roots first (e.g. deps/ source roots), then one group per env.
        val result = mutableListOf<AbstractTreeNode<*>>()
        byEnv[null]?.let { result.addAll(it) }
        for (env in envKeys) {
            result.add(BuildEnvGroupNode(project, env, byEnv[env]!!, settings))
        }
        return result
    }

    /**
     * Presents one SDK application as a single node holding both its beams and its source.
     *
     * An SDK node's children are its `CLASSES` roots and nothing else -
     * `LibraryGroupNode.addLibraryChildren` calls `getRootFiles(OrderRootType.CLASSES)` for a
     * `JdkOrderEntry`, unlike a library entry, which contributes its source roots too. So the `src`
     * or `lib` beside an `ebin` never arrives as a sibling here and has to be built.
     *
     * An application shipping only beams has no source sibling; there is nothing to tell apart, so
     * that level is skipped and its `ebin` node stands as it is, named after the application by
     * [ElixirSdkLibraryNodeDecorator].
     */
    private fun tryGroupEbinWithSource(
        node: AbstractTreeNode<*>,
        project: Project,
        settings: ViewSettings,
    ): AbstractTreeNode<*>? {
        if (node !is PsiDirectoryNode) return null
        val ebinVFile = node.value?.virtualFile ?: return null
        if (ebinVFile.name != "ebin") return null

        // O(1) set lookup - avoids scanning all SDKs per child
        if (ebinVFile !in ElixirSdkRootsCache.classRoots()) return null

        val applicationVFile = ebinVFile.parent ?: return null
        val sourceVFile = SOURCE_DIRECTORY_NAMES
            .firstNotNullOfOrNull { name -> applicationVFile.findChild(name)?.takeIf { it.isDirectory } }
            ?: return null
        val psiSourceDir = PsiManager.getInstance(project).findDirectory(sourceVFile) ?: return null

        return ApplicationGroupNode(
            project,
            applicationVFile.name,
            listOf(node, PsiDirectoryNode(project, psiSourceDir, settings)),
            settings,
        )
    }
}

/**
 * A virtual (non-PSI) tree node representing one SDK application (e.g. `stdlib-7.1`, `iex`).
 *
 * Groups that application's `ebin` with the `lib` or `src` beside it, which would otherwise appear
 * as separate roots each named after the same application.
 */
private class ApplicationGroupNode(
    project: Project,
    applicationName: String,
    private val applicationChildren: List<AbstractTreeNode<*>>,
    settings: ViewSettings,
) : ProjectViewNode<String>(project, applicationName, settings) {
    override fun getChildren(): Collection<AbstractTreeNode<*>> = applicationChildren
    override fun update(presentation: PresentationData) {
        presentation.presentableText = value
        presentation.setIcon(AllIcons.Nodes.Folder)
    }
    override fun contains(file: VirtualFile): Boolean =
        applicationChildren.any { (it as? ProjectViewNode<*>)?.contains(file) == true }
}

/**
 * Whether this SDK order entry points at an Elixir or Erlang SDK, matching by SDK type id the same way
 * [ElixirSdkRootsCache] does. Used to avoid re-weighting SDKs of unrelated projects (e.g. Java), since this
 * provider is registered application-wide.
 */
private fun JdkOrderEntry.isElixirOrErlangSdk(): Boolean {
    val typeName = jdk?.sdkType?.name ?: return false
    return typeName == ElixirSdkTypeId.ELIXIR_SDK_TYPE_ID || typeName == ErlangSdkTypeId.ERLANG_SDK_TYPE_ID
}

/**
 * A virtual (non-PSI) tree node representing a Mix build environment (e.g. `dev` or `test`).
 * Groups `ebin` and `consolidated` roots that would otherwise appear as indistinguishable duplicates.
 */
private class BuildEnvGroupNode(
    project: Project,
    envName: String,
    private val envChildren: List<AbstractTreeNode<*>>,
    settings: ViewSettings,
) : ProjectViewNode<String>(project, envName, settings) {
    override fun getChildren(): Collection<AbstractTreeNode<*>> = envChildren
    override fun update(presentation: PresentationData) {
        presentation.presentableText = value
        presentation.setIcon(AllIcons.Nodes.Folder)
    }
    override fun contains(file: VirtualFile): Boolean =
        envChildren.any { (it as? ProjectViewNode<*>)?.contains(file) == true }
}

/**
 * Wraps an SDK [NamedLibraryElementNode] (names starting with `<`) with weight -2 so it sorts
 * before consolidated libraries (weight -1) and all standard dep libraries (weight 0).
 */
private class SdkLibraryNode(
    wrapped: NamedLibraryElementNode,
    settings: ViewSettings,
) : NamedLibraryElementNode(wrapped.project!!, wrapped.value!!, settings) {
    override fun getWeight(): Int = -2
}

/**
 * Wraps a consolidated [NamedLibraryElementNode] with weight -1 so IntelliJ's tree sorter places
 * it after SDK nodes (weight -2) but before all standard dep nodes (weight 0).
 */
private class ConsolidatedLibraryNode(
    wrapped: NamedLibraryElementNode,
    settings: ViewSettings,
) : NamedLibraryElementNode(wrapped.project!!, wrapped.value!!, settings) {
    override fun getWeight(): Int = -1

    override fun update(presentation: PresentationData) {
        super.update(presentation)
        presentScoped(presentation)
    }
}

/**
 * Wraps a scoped dep [NamedLibraryElementNode] whose name carries a `[<scope>]` suffix,
 * overriding [update] to display the dep name without the verbose URL and instead show the
 * content root directory as a grey location string.
 *
 * Example: `"phoenix [apps/my_app]"` displays as `phoenix` with `my_app` in grey; a dep scoped to
 * the project root displays as `phoenix` alone.
 */
private class ScopedDepLibraryNode(
    wrapped: NamedLibraryElementNode,
    settings: ViewSettings,
) : NamedLibraryElementNode(wrapped.project!!, wrapped.value!!, settings) {
    override fun update(presentation: PresentationData) {
        super.update(presentation)
        presentScoped(presentation)
    }
}

private fun presentScoped(presentation: PresentationData) {
    val name = presentation.presentableText ?: return
    // Parsed through the naming helper rather than by matching a literal prefix: the scope
    // token is a project-relative path, and only falls back to a `file://` URL where no
    // relative path can express it.
    val token = scopedLibraryNameToken(name) ?: return
    val bracketIdx = name.indexOf(" [")
    if (bracketIdx <= 0) return
    presentation.presentableText = name.substring(0, bracketIdx)
    // Final segment only, as a location hint. The project's own root is "." and needs no hint -
    // every dep without one sits there.
    presentation.locationString = token
        .trimEnd('/')
        .substringAfterLast('/')
        .takeUnless { it == "." || it.isEmpty() }
}

/**
 * Siblings of an `ebin` that hold source, in the order they are preferred. An application written in Elixir keeps its
 * `.ex` under `lib`; OTP's own applications keep their `.erl` under `src`.
 */
private val SOURCE_DIRECTORY_NAMES = listOf("lib", "src")
