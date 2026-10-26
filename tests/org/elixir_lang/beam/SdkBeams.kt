package org.elixir_lang.beam

import java.io.File

/** A single `.beam` shipped by a resolved SDK, paired with a stable `"<tag>/<app>/<module>.beam"` label. */
data class SdkBeam(val label: String, val file: File)

/**
 * Enumerates every `.beam` an SDK ships under `<root>/lib/<app>/ebin/`, sorted by app then module.
 *
 * Shared by every SDK-wide sweep so their beam discovery can't drift apart: each turns an [SdkBeam] into a test of
 * its own. [SdkBeamParseTest] is platform-free, so one cheap instance per beam; [SdkStdlibSweepTest] decompiles, which
 * needs the platform fixture, so its tests share one.
 */
object SdkBeams {
    fun forSdk(root: String?, tag: String): List<SdkBeam> {
        if (root.isNullOrEmpty()) return emptyList()

        return File(root, "lib").listFiles().orEmpty()
            .sortedBy { it.name }
            .flatMap { app ->
                File(app, "ebin").listFiles { f -> f.name.endsWith(".beam") }.orEmpty()
                    .sortedBy { it.name }
                    .map { beam -> SdkBeam("$tag/${app.name}/${beam.name}", beam) }
            }
    }
}
