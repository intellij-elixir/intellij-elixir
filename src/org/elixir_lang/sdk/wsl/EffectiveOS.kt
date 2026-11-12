package org.elixir_lang.sdk.wsl

import com.intellij.execution.wsl.WslPath
import com.intellij.util.system.OS

/** The OS an SDK at [homePath] runs on: Linux for a WSL home on Windows, otherwise this machine's. */
fun effectiveOS(homePath: String?): OS {
    if (OS.CURRENT == OS.Windows) {
        homePath?.let { if (WslPath.isWslUncPath(it)) return OS.Linux }
    }
    return OS.CURRENT
}
