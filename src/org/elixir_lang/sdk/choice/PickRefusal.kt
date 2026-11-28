package org.elixir_lang.sdk.choice

import com.intellij.ide.JavaUiBundle
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.util.NlsContexts

/** Why a wizard refuses [sdk] as its pick, or null when it takes it: no SDK, or one [pairingRefusal] refuses. */
internal fun pickRefusal(sdk: Sdk?): @NlsContexts.DialogMessage String? =
    if (sdk == null) JavaUiBundle.message("title.no.jdk.specified") else pairingRefusal(sdk)
