package org.elixir_lang.psi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "Does this call put a function or macro name in scope" is answered by [CallableDeclaration] alone. A file under
 * `src/org/elixir_lang` that names two or more of the declaring predicates - directly, or through an import that
 * lets it call one bare - is answering it again by hand, which is how three scope walkers came to disagree about
 * which forms declare. A site that treats each form differently dispatches on the form, exhaustively.
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
            enumerating.keys.sorted().joinToString("\n") { "$it: ${enumerating.getValue(it)}" }
        )
    }

    /**
     * What a declaration can do is answered by [CallableDeclaration.capabilitiesOf] alone; nothing names the clause
     * kind predicates it replaced outside the mechanism.
     */
    @Test
    fun `only CallableDeclaration decides whether a declaration is a function or a macro`() {
        assertTrue("expected $ROOT to exist - is the working directory the project root?", ROOT.isDirectory)

        val deciding = ROOT.walkTopDown()
            .filter { it.isFile && it.extension in SOURCE_EXTENSIONS }
            .map { it.relativeTo(ROOT).invariantSeparatorsPath to it }
            .filter { (path, _) -> path !in KIND_MECHANISM }
            .filter { (_, file) ->
                val code = file.readLines()
                    .filterNot { COMMENT_LINE.containsMatchIn(it) }
                    .joinToString("\n") { it.replace(TRAILING_COMMENT, "") }

                KIND_QUALIFIED.containsMatchIn(code) ||
                    KIND_IMPORT.containsMatchIn(code) ||
                    CLAUSE_ALIAS_IMPORT.findAll(code).any { import ->
                        Regex("""(?<![A-Za-z_])${import.groupValues[1]}$COMPANION\s*(?:\.|::)\s*$KIND\b""")
                            .containsMatchIn(code)
                    }
            }
            .map { (path, _) -> path }
            .sorted()
            .toList()

        assertEquals("Ask CallableDeclaration.capabilitiesOf instead of naming the clause kind predicates:", "", deciding.joinToString("\n"))
    }

    /** A compiled definition's capabilities come from `CallableDeclaration.capabilitiesOf` too, not from its `time`. */
    @Test
    fun `only CallableDeclaration decides what a compiled definition can do from its time`() {
        assertTrue("expected $ROOT to exist - is the working directory the project root?", ROOT.isDirectory)

        val deciding = ROOT.walkTopDown()
            .filter { it.isFile && it.extension in SOURCE_EXTENSIONS }
            .map { it.relativeTo(ROOT).invariantSeparatorsPath to it }
            .filter { (path, _) -> path !in KIND_MECHANISM }
            .filter { (_, file) ->
                val code = file.readLines()
                    .filterNot { COMMENT_LINE.containsMatchIn(it) }
                    .joinToString("\n") { it.replace(TRAILING_COMMENT, "") }

                COMPILED_TIME.containsMatchIn(code)
            }
            .map { (path, _) -> path }
            .sorted()
            .toList()

        assertEquals("Ask CallableDeclaration.capabilitiesOf instead of reading a compiled definition's time:", "", deciding.joinToString("\n"))
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
        val KIND_MECHANISM = setOf("psi/CallDefinitionClause.kt", "psi/$MECHANISM")
        private const val KIND = """`?is(?:Function|Macro|Guard|Public\w*|Private\w*)`?"""
        private const val CLAUSE = """org\.elixir_lang\.psi\.CallDefinitionClause"""
        val KIND_QUALIFIED = Regex("""(?<![A-Za-z_])CallDefinitionClause$COMPANION\s*(?:\.|::)\s*$KIND\b""")
        val KIND_IMPORT = Regex("""import\s+(?:static\s+)?$CLAUSE(?:\.Companion|\.INSTANCE)?\.$KIND\b""")
        // A compiled definition's `time` property - `definition.time == Timed.Time.COMPILE`, `when (definition.time)` -
        // unlike a structure-view element's `time()`.
        val COMPILED_TIME = Regex("""\.time\s*==\s*(?:Timed\.)?Time\.|when\s*\(\s*[\w.]+\.time\s*\)""")
        val CLAUSE_ALIAS_IMPORT =Regex("""import\s+(?:static\s+)?$CLAUSE\s+as\s+(\w+)""")
        val COMMENT_LINE = Regex("""^\s*(//|\*|/\*)""")
        val TRAILING_COMMENT = Regex("""\s//.*$""")
    }
}
