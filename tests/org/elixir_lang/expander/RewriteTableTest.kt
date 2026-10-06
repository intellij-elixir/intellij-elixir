package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.elixir_surface.LegManifest
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The expander's `inline/3`, `rewrite/5` and guard functions against every leg's committed [RewriteManifestTest]
 * manifests, row for row, at that leg's Elixir and Erlang/OTP.
 */
class RewriteTableTest {
    @Test
    fun `the expander inlines what each leg inlines`() {
        val versions = RewriteManifests.versions(RewriteManifestTest.INLINE)
        val calls = versions.flatMap { RewriteManifests.inline(it).keys }.toSet() + INLINED.keys

        assertTrue("no committed inline manifest", versions.isNotEmpty())
        assertEquals(
            versions.joinToString("\n") { version ->
                "$version\n" + render(RewriteManifests.inline(version).mapValues { (_, erlang) -> erlang.toList() })
            },
            versions.joinToString("\n") { version ->
                val level = ElixirLanguageLevel.of(version)

                val inlined = calls.mapNotNull { call ->
                    inline(call.first, call.second, call.third, level)?.let { call to it.toList() }
                }

                "$version\n" + render(inlined.toMap())
            },
        )
    }

    @Test
    fun `the expander rewrites what each leg rewrites`() {
        val versions = RewriteManifests.versions(RewriteManifestTest.REWRITE)
        val calls = versions.flatMap { RewriteManifests.rewrite(it).keys }.toSet() + REWRITTEN.keys

        assertTrue("no committed rewrite manifest", versions.isNotEmpty())
        assertEquals(
            versions.joinToString("\n") { version -> "$version\n" + render(RewriteManifests.rewrite(version)) },
            versions.joinToString("\n") { version ->
                val level = ElixirLanguageLevel.of(version)

                val rewritten = calls.mapNotNull { call ->
                    rewrite(call.first, call.second, call.third, level)?.let {
                        call to listOf(it.erlangModule, it.erlangName, it.erlangArity.toString())
                    }
                }

                "$version\n" + render(rewritten.toMap())
            },
        )
    }

    @Test
    fun `the expander admits in a guard what each leg admits`() {
        val versions = RewriteManifests.versions(RewriteManifestTest.GUARD_FUNCTIONS)
        val functions = versions.flatMap { RewriteManifests.guardFunctions(it) }.toSet() + OTP_24_GUARD_BIFS +
            GUARD_BIFS_SINCE.keys

        assertTrue("no committed guard manifest", versions.isNotEmpty())
        assertEquals(
            versions.joinToString("\n") { version ->
                "$version\n" + render(RewriteManifests.guardFunctions(version))
            },
            versions.joinToString("\n") { version ->
                val level = ElixirLanguageLevel.of(version, RewriteManifests.otp(version))
                val admitted = functions.filter { isAllowedInGuard(ERLANG, it.name, it.arity, level) }

                "$version\n" + render(admitted.toSet())
            },
        )
    }

    private fun render(table: Map<Triple<String, String, Int>, List<String>>): String =
        table.map { (call, erlang) -> "  ${call.first} ${call.second} ${call.third} ${erlang.joinToString(" ")}" }
            .sorted()
            .joinToString("\n")

    private fun render(functions: Set<NameArity>): String =
        functions.map { "  ${it.name} ${it.arity}" }.sorted().joinToString("\n")
}

/** The manifests [RewriteManifestTest] commits for each leg. */
internal object RewriteManifests {
    /** Each version with a committed [name], in version order. */
    fun versions(name: String): List<String> =
        File(LegManifest.ROOT)
            .listFiles { directory -> File(directory, name).isFile }
            .orEmpty()
            .map { it.name }
            .sortedWith(com.intellij.util.text.VersionComparatorUtil.COMPARATOR)

    /** [version]'s `inline/3` table: each call's `{erlang_module, erlang_name}`. */
    fun inline(version: String): Map<Triple<String, String, Int>, Pair<String, String>> =
        rows(version, RewriteManifestTest.INLINE).associate { row ->
            val (module, name, arity, erlangModule, erlangName) = row

            Triple(module, name, arity.toInt()) to (erlangModule to erlangName)
        }

    /** [version]'s `rewrite/5` table: each call's `[erlang_module, erlang_name, erlang_arity]`. */
    fun rewrite(version: String): Map<Triple<String, String, Int>, List<String>> =
        rows(version, RewriteManifestTest.REWRITE).associate { row ->
            val (module, name, arity) = row

            Triple(module, name, arity.toInt()) to row.drop(3)
        }

    /** The `:erlang` functions [version]'s leg admits in a guard. */
    fun guardFunctions(version: String): Set<NameArity> =
        rows(version, RewriteManifestTest.GUARD_FUNCTIONS)
            .filterNot { it.first() == OTP }
            .map { (name, arity) -> NameArity(name, arity.toInt()) }
            .toSet()

    /** The Erlang/OTP major release the leg that generated [version]'s guard functions ran on. */
    fun otp(version: String): String =
        rows(version, RewriteManifestTest.GUARD_FUNCTIONS).first { it.first() == OTP }[1]

    private fun rows(version: String, name: String): List<List<String>> =
        File("${LegManifest.ROOT}/$version/$name").readLines()
            .filterNot { it.startsWith("#") || it.isBlank() }
            .map { it.split(" ") }

    private const val OTP = "otp"
}
