package org.elixir_lang.sdk.choice

import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.util.NlsContexts
import org.elixir_lang.sdk.erlang_dependent.getErlangSdk
import org.elixir_lang.sdk.wsl.wslCompat

/**
 * Why a pick of the Elixir SDK [sdk] is refused, or null when it is taken: Mix needs the Erlang SDK it is paired with,
 * and both homes. Kept apart from [pickRefusal], whose no-SDK text is the Java plugin's, so the chooser reaches no Java
 * class on the small IDEs.
 */
internal fun pairingRefusal(sdk: Sdk): @NlsContexts.DialogMessage String? {
    val internalErlangSdk = sdk.getErlangSdk()
        ?: return "Internal Erlang SDK is not configured. Set it before picking this Elixir SDK."

    return homeRefusal("Internal Erlang SDK", internalErlangSdk) ?: homeRefusal("Elixir SDK", sdk)
}

/** Why [sdk], called [label], can't be used: its home is in a WSL distribution that is gone, or isn't there. */
private fun homeRefusal(label: String, sdk: Sdk): @NlsContexts.DialogMessage String? {
    val home = sdk.homePath
    if (home != null && !wslCompat.isReachable(home)) {
        return "$label (${sdk.name}) home directory ($home) is in a WSL distribution that is not installed."
    }
    if (home?.let { wslCompat.findFileByPath(it) } == null) {
        return "$label (${sdk.name}) home directory ($home) does not exist. Did you uninstall it?"
    }

    return null
}
