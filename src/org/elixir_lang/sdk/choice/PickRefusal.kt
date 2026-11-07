package org.elixir_lang.sdk.choice

import com.intellij.ide.JavaUiBundle
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.util.NlsContexts
import org.elixir_lang.sdk.erlang_dependent.getErlangSdk
import org.elixir_lang.sdk.wsl.wslCompat

/** Why a wizard refuses [sdk] as its pick, or null when it takes it: Mix needs the Erlang SDK it is paired with. */
internal fun pickRefusal(sdk: Sdk?): @NlsContexts.DialogMessage String? {
    sdk ?: return JavaUiBundle.message("title.no.jdk.specified")

    val internalErlangSdk = sdk.getErlangSdk()
        ?: return "Internal Erlang SDK is not configured. Set it before picking this Elixir SDK."

    val internalErlangHome = internalErlangSdk.homePath
    if (internalErlangHome != null && !wslCompat.isReachable(internalErlangHome)) {
        return "Internal Erlang SDK (${internalErlangSdk.name}) home directory ($internalErlangHome) is in a WSL" +
                " distribution that is not installed."
    }
    if (internalErlangHome?.let { wslCompat.findFileByPath(it) } == null) {
        return "Internal Erlang SDK (${internalErlangSdk.name}) home directory" +
                " ($internalErlangHome) does not exist. Did you uninstall it?"
    }

    return null
}
