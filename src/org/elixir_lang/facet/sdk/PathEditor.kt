package org.elixir_lang.facet.sdk

import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.projectRoots.SdkModificator
import com.intellij.openapi.projectRoots.ui.SdkPathEditor
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.VirtualFile


private val DISPLAY_NAME_BY_ORDER_ROOT_TYPE = mapOf<OrderRootType, String>(
        // https://github.com/JetBrains/intellij-community/blob/da94359b1054070cb7d7280673a773daf87cc7d8/java/idea-ui/src/com/intellij/openapi/roots/ui/configuration/libraryEditor/ClassesOrderRootTypeUIFactory.java#L53
        OrderRootType.CLASSES to "Classpath",
        // https://github.com/JetBrains/intellij-community/blob/da94359b1054070cb7d7280673a773daf87cc7d8/java/idea-ui/src/com/intellij/openapi/roots/ui/configuration/libraryEditor/SourcesOrderRootTypeUIFactory.java#L57
        OrderRootType.SOURCES to "Sourcepath"
)

fun pathEditor(orderRootType: OrderRootType): RebasingSdkPathEditor? =
        DISPLAY_NAME_BY_ORDER_ROOT_TYPE[orderRootType]?.let { displayName ->
            RebasingSdkPathEditor(
                    displayName,
                    orderRootType,
                    FileChooserDescriptor(true, true, false, false, false, true)
            )
        }

/**
 * A path list that keeps its roots as they last matched the copy, at [reset] and [apply], so [rebase] can tell the
 * user's own adds and removes.
 */
class RebasingSdkPathEditor(displayName: String, orderRootType: OrderRootType, descriptor: FileChooserDescriptor) :
    SdkPathEditor(displayName, orderRootType, descriptor) {
    private var matched: Set<VirtualFile> = emptySet()

    override fun reset(modificator: SdkModificator?) {
        super.reset(modificator)
        matched = roots.toSet()
    }

    override fun apply(sdkModificator: SdkModificator) {
        super.apply(sdkModificator)
        matched = roots.toSet()
    }

    /** Shows [modificator]'s roots with the user's own adds and removes since the list last matched the copy. */
    fun rebase(modificator: SdkModificator) {
        val shown = roots.toList()
        val added = shown.filterNot(matched::contains)
        val removed = matched.filterNot(shown::contains)
        reset(modificator)
        if (removed.isNotEmpty()) removePaths(*removed.toTypedArray())
        if (added.isNotEmpty()) addPaths(*added.toTypedArray())
    }
}
