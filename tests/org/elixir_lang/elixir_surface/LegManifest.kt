package org.elixir_lang.elixir_surface

import com.intellij.testFramework.UsefulTestCase
import com.intellij.util.text.VersionComparatorUtil
import org.elixir_lang.golden.CommittedGolden
import java.io.File
import kotlin.reflect.KClass

/** Manifests committed per Elixir version under `testData/org/elixir_lang/elixir_surface/<version>/`. */
object LegManifest {
    const val ROOT = "testData/org/elixir_lang/elixir_surface"

    fun ebin(): File = File(environment("ELIXIR_LANG_ELIXIR_PATH"), "lib/elixir/ebin")

    /** Fails unless [body] matches the manifest [name] committed for the leg's Elixir version, which [test] regenerates. */
    fun assertMatchesLeg(test: KClass<*>, name: String, body: String) {
        val elixirVersion = environment("ELIXIR_VERSION")

        assertMatchesFile(
            ROOT,
            elixirVersion,
            name,
            "# ${regenerate(test)}\n$body",
            regenerateCommand(test, elixirVersion, environment("ERLANG_VERSION"))
        )
    }

    /**
     * [CommittedGolden.assertMatches], except that a missing manifest fails without being written: the platform
     * would otherwise write whatever the SDK produced as the expected manifest.
     */
    fun assertMatchesFile(
        root: String,
        version: String,
        name: String,
        actual: String,
        regenerate: String,
        writeMissing: Boolean = UsefulTestCase.OVERWRITE_TESTDATA,
    ) {
        val path = path(root, version, name)

        if (!File(path).isFile && !writeMissing) {
            val error = AssertionError("No committed manifest at $path. Review the SDK's manifest, then regenerate: $regenerate\n$actual")

            earlierVersion(root, version, name)?.let { earlier ->
                val earlierPath = path(root, earlier, name)

                CommittedGolden.appendToStepSummary(
                    "`$path` is not committed; compared with `$earlierPath`",
                    earlierPath,
                    File(earlierPath).readText(),
                    path,
                    actual,
                    regenerate
                )?.let(error::addSuppressed)
            }

            throw error
        }

        CommittedGolden.assertMatches(path, actual, regenerate)
    }

    /** Where the file [name] committed for [version] under [root] is. */
    fun path(root: String, version: String, name: String): String = "$root/$version/$name"

    /** The highest version below [version] that has [name]. */
    private fun earlierVersion(root: String, version: String, name: String): String? =
        File(root)
            .listFiles { directory -> File(directory, name).isFile }
            .orEmpty()
            .map { it.name }
            .filter { VersionComparatorUtil.compare(it, version) < 0 }
            .maxWithOrNull(VersionComparatorUtil.COMPARATOR)

    /**
     * [regenerate] alone resolves the local SDK, not the leg that failed. The version flags are quoted because
     * PowerShell splits an unquoted `-P` argument at its first dot.
     */
    fun regenerateCommand(test: KClass<*>, elixirVersion: String, otpVersion: String): String =
        "${regenerate(test)} \"-PelixirVersion=$elixirVersion\" \"-PotpVersion=$otpVersion\""

    private fun regenerate(test: KClass<*>): String =
        "./gradlew test --tests ${test.qualifiedName} -PoverwriteTestData=true"

    fun environment(name: String): String =
        System.getenv(name).takeUnless { it.isNullOrEmpty() } ?: throw AssertionError("$name not set for the test JVM")
}
