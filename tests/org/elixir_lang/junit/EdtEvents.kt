package org.elixir_lang.junit

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.ui.EDT

// Under a mock application, which always allows writes, the platform refuses to dispatch.
internal fun dispatchEdtEvents() {
    if (EDT.isCurrentThreadEdt() && !ApplicationManager.getApplication().isWriteAccessAllowed) {
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }
}
