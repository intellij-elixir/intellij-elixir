package org.elixir_lang.psi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "Does this call put a function or macro name in scope" is answered by [CallableDeclaration] alone. A file under
 * `src/org/elixir_lang` that names two or more of the declaring predicates - directly, or through an import that
 * lets it call one bare - is answering it again by hand, which is how three scope walkers came to disagree about
 * which forms declare.
 *
 * [NOT_YET_ASKING] lists the files that still do. Migrating one deletes its row; a row whose file no longer
 * enumerates fails too, so the list only shrinks.
 *
 * [CallableDeclaration.formOf] resolves a reference for two of the six forms, so a caller on the stub-building path
 * must not ask it. The failure message says which to ask instead; this guard only counts predicate names.
 */
class CallableDeclarationGuardTest {
    @Test
    fun `only CallableDeclaration enumerates the forms that declare a callable`() {
        assertTrue("expected $ROOT to exist - is the working directory the project root?", ROOT.isDirectory)

        val enumerating = enumeratingFiles()

        assertEquals(
            """
            Ask CallableDeclaration instead of naming these predicates - formOf or declares when the caller may
            resolve a reference, syntacticFormOf where resolution is illegal (stub building), headBindingFormOf when
            only a parameter-binding head matters:
            """.trimIndent(),
            "",
            (enumerating.keys - NOT_YET_ASKING).sorted().joinToString("\n") { "$it: ${enumerating.getValue(it)}" }
        )
        assertEquals(
            "No longer enumerating; delete the row:",
            "",
            (NOT_YET_ASKING - enumerating.keys).sorted().joinToString("\n")
        )
    }

    private fun enumeratingFiles(): Map<String, List<String>> =
        ROOT.walkTopDown()
            .filter { it.isFile && it.extension in SOURCE_EXTENSIONS && it.name != MECHANISM }
            .associate { file ->
                val code = file.readLines()
                    .filterNot { COMMENT_LINE.containsMatchIn(it) }
                    .joinToString("\n") { it.replace(TRAILING_COMMENT, "") }
                val named = PREDICATES.filter { predicate ->
                    predicate.enumerating.containsMatchIn(code) ||
                        (predicate.importedMember.containsMatchIn(code) && predicate.bareCall.containsMatchIn(code)) ||
                        predicate.callsThroughAlias(code)
                }.map { it.name }

                file.relativeTo(ROOT).invariantSeparatorsPath to named
            }
            .filterValues { it.size >= 2 }

    /**
     * [importedMember] and [bareCall] together catch a file that imports one predicate's member and calls it
     * unqualified, which [enumerating] alone - written against the qualified form - would miss. [owner] and [member]
     * catch the third spelling, `import ...Owner as Alias` then `Alias.member(`, which neither sees.
     */
    private class Predicate(
        val name: String,
        val enumerating: Regex,
        val importedMember: Regex,
        val bareCall: Regex,
        val owner: String,
        val member: String,
    ) {
        private val aliasImport = Regex("""import\s+(?:static\s+)?$owner\s+as\s+(\w+)""")

        fun callsThroughAlias(code: String): Boolean =
            aliasImport.findAll(code).any { import ->
                Regex("""(?<![A-Za-z_])${import.groupValues[1]}$COMPANION\s*\.\s*$member\s*\(""").containsMatchIn(code)
            }
    }

    private companion object {
        val ROOT = File("src/org/elixir_lang")
        const val MECHANISM = "CallableDeclaration.kt"
        val SOURCE_EXTENSIONS = setOf("kt", "java")

        // Kotlin's `Owner.Companion.member` and Java's `Owner.INSTANCE.member` for an `object` member.
        private const val COMPANION = """(\s*\.\s*(?:Companion|INSTANCE))?"""
        // Requires the call's open parenthesis, so a method reference passed as a predicate is not seen, as at
        // `documentation/ElixirDocumentationProvider.kt:184`.
        private const val IS = """\s*\.\s*`?is`?\s*\("""
        private val BARE_IS = Regex("""(?<![.\w])`?is`?\s*\(""")

        private const val IS_MEMBER = """`?is`?"""

        fun isMember(owner: String) =
            Predicate(
                owner.lowercase(),
                Regex("""(?<![A-Za-z_])$owner$COMPANION$IS"""),
                Regex("""import\s+(?:static\s+)?org\.elixir_lang(?:\.[\w]+)*\.$owner(?:\.Companion)?\.`is`"""),
                BARE_IS,
                """org\.elixir_lang(?:\.\w+)*\.$owner""",
                IS_MEMBER,
            )

        val PREDICATES = listOf(
            Predicate(
                "clause",
                Regex("""CallDefinitionClause$COMPANION\s*\.\s*`?is(Function|Macro|Guard)?`?\s*\("""),
                Regex("""import\s+(?:static\s+)?org\.elixir_lang\.psi\.CallDefinitionClause(?:\.Companion)?\.`is`"""),
                BARE_IS,
                """org\.elixir_lang\.psi\.CallDefinitionClause""",
                """`?is(?:Function|Macro|Guard)?`?""",
            ),
            isMember("Callback"),
            isMember("Delegation"),
            Predicate(
                "exception",
                Regex("""(?<![A-Za-z_.])Exception$COMPANION$IS|elixir_lang\.psi\.Exception$COMPANION$IS"""),
                Regex("""import\s+(?:static\s+)?org\.elixir_lang\.psi\.Exception\.`is`"""),
                BARE_IS,
                """org\.elixir_lang\.psi\.Exception""",
                IS_MEMBER,
            ),
            Predicate(
                "eex",
                Regex("""(?<![A-Za-z_])EEx$COMPANION\s*\.\s*isFunctionFrom\s*\("""),
                Regex("""import\s+(?:static\s+)?org\.elixir_lang\.EEx\.isFunctionFrom"""),
                Regex("""(?<![.\w])isFunctionFrom\s*\("""),
                """org\.elixir_lang\.EEx""",
                "isFunctionFrom",
            ),
            Predicate(
                "generator",
                Regex("""(?<![A-Za-z_])Generator$COMPANION\s*\.\s*isEmbed\s*\("""),
                Regex("""import\s+(?:static\s+)?org\.elixir_lang\.psi\.mix\.Generator\.isEmbed"""),
                Regex("""(?<![.\w])isEmbed\s*\("""),
                """org\.elixir_lang\.psi\.mix\.Generator""",
                "isEmbed",
            ),
        )
        val COMMENT_LINE = Regex("""^\s*(//|\*|/\*)""")
        val TRAILING_COMMENT = Regex("""\s//.*$""")

        val NOT_YET_ASKING = setOf(
            "navigation/ChooseByNameContributor.kt",
            "psi/ElementDescriptionProvider.kt",
            "psi/impl/PsiNameIdentifierOwnerImpl.kt",
            "reference/Callable.kt",
            "structure_view/ChildCall.kt",
        )
    }
}
