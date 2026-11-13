@file:JvmName("ElixirSdkExtensions")
package org.elixir_lang.sdk.erlang_dependent

import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkModel
import org.elixir_lang.notification.setup_sdk.Notifier

val Sdk.elixirAdditionalData: SdkAdditionalData?
    get() = sdkAdditionalData as? SdkAdditionalData

fun Sdk.getErlangSdk(): Sdk? = ErlangSdkResolver.getInstance().resolveErlangSdk(this)

fun Sdk.resolveErlangSdkOrNullAndNotify(
    sdkModel: SdkModel? = null,
    project: Project? = null,
): Sdk? {
    return when (val resolution = ErlangSdkResolver.getInstance().resolveErlangSdkResult(this, sdkModel)) {
        is ErlangSdkResult.Success -> resolution.sdk
        is ErlangSdkResult.Missing -> {
            Notifier.elixirSdkMissingErlangDependency(project, resolution)
            null
        }
    }
}
