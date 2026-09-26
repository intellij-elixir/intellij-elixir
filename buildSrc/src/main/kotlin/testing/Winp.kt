package testing

import org.gradle.api.tasks.testing.Test
import java.io.File

/**
 * WinP, which the platform kills and interrupts processes through on Windows, otherwise unpacks its native helpers
 * beside its jar: into the shared, immutable IDE transform, which Gradle then deletes and re-extracts on the next build,
 * failing while a sandbox runs from it. The IDE's own startup sets this; tests never run that startup.
 */
fun Test.keepWinpHelpersIn(dir: File) {
    // Created, or WinP unpacks a randomly named copy into the system temp directory on every run.
    doFirst { dir.mkdirs() }
    systemProperty("winp.folder.preferred", dir.absolutePath)
}
