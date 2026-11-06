package org.elixir_lang.sdk

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.util.Disposer
import java.util.Collections
import java.util.IdentityHashMap

/** Which SDKs a write to the [ProjectJdkTable] touched, for a holder of a copy of the table to bring itself in line. */
internal object SdkTableChanges {
    enum class Kind { ADDED, REMOVED, RENAMED, ROOTS_COMMITTED }

    /** Runs inside the write action that touched [sdk], on whatever thread that is, so it may only record or clear. */
    fun interface Listener {
        fun touched(sdk: Sdk, kind: Kind)
    }

    /**
     * Calls [listener] for each SDK that [filter] accepts when the table adds, removes or renames it, and when a commit
     * changes it, which fires no table event, only `rootSetChanged` on the SDK's own root provider. SDKs added later are
     * followed too. It stops when [parent] is disposed.
     */
    fun follow(parent: Disposable, filter: (Sdk) -> Boolean, listener: Listener) {
        // One child of [parent] per SDK, disposed when the table removes it: a listener on its root provider lives in
        // the Disposer tree, so it would otherwise hold the removed SDK as long as [parent] lives.
        val followed = Collections.synchronizedMap(IdentityHashMap<Sdk, Disposable>())

        fun followCommits(sdk: Sdk) {
            val following = Disposer.newDisposable(parent, "SdkTableChanges ${sdk.name}")
            followed[sdk] = following
            sdk.rootProvider.addRootSetChangedListener({ listener.touched(sdk, Kind.ROOTS_COMMITTED) }, following)
        }

        ApplicationManager.getApplication().messageBus.connect(parent)
            .subscribe(ProjectJdkTable.JDK_TABLE_TOPIC, object : ProjectJdkTable.Listener {
                override fun jdkAdded(jdk: Sdk) {
                    if (!filter(jdk)) return
                    followCommits(jdk)
                    listener.touched(jdk, Kind.ADDED)
                }

                override fun jdkRemoved(jdk: Sdk) {
                    if (filter(jdk)) listener.touched(jdk, Kind.REMOVED)
                    followed.remove(jdk)?.let(Disposer::dispose)
                }

                override fun jdkNameChanged(jdk: Sdk, previousName: String) {
                    if (filter(jdk)) listener.touched(jdk, Kind.RENAMED)
                }
            })
        ProjectJdkTable.getInstance().allJdks.filter(filter).forEach(::followCommits)
    }
}
