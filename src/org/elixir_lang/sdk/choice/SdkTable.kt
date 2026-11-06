package org.elixir_lang.sdk.choice

import com.intellij.openapi.application.ApplicationManager

/**
 * Writes the SDK table to `jdk.table.xml`, with every other unsaved setting. While the project loads,
 * `DelayedProjectSynchronizer`'s retry (Attempt 2) keeps only the SDKs `jdk.table.xml` has, so an SDK registered but
 * still only in memory would be removed, undoing the module assignment made with it.
 */
internal fun saveSdkTable() = ApplicationManager.getApplication().saveSettings()
