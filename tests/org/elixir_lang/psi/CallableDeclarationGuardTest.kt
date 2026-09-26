package org.elixir_lang.psi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "Does this call put a function or macro name in scope" is answered by [CallableDeclaration] alone. A file under
 * `src/org/elixir_lang` that names two or more of the declaring forms - through their predicates, directly or through
 * an import that lets it call one bare, or through `isForm` - is answering it again by hand, which is how three scope walkers came to disagree about
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

        val deciding = sourceFiles()
            .filter { (path, _) -> path !in KIND_MECHANISM }
            .filter { (_, code) ->
                KIND_QUALIFIED.containsMatchIn(code) ||
                    KIND_IMPORT.containsMatchIn(code) ||
                    CLAUSE_ALIAS_IMPORT.findAll(code).any { import ->
                        Regex("""(?<![A-Za-z_])${import.groupValues[1]}$COMPANION\s*(?:\.|::)\s*$KIND\b""")
                            .containsMatchIn(code)
                    }
            }
            .keys
            .sorted()

        assertEquals("Ask CallableDeclaration.capabilitiesOf instead of naming the clause kind predicates:", "", deciding.joinToString("\n"))
    }

    /** A compiled definition's capabilities come from `CallableDeclaration.capabilitiesOf` too, not from its `time`. */
    @Test
    fun `only CallableDeclaration decides what a compiled definition can do from its time`() {
        assertTrue("expected $ROOT to exist - is the working directory the project root?", ROOT.isDirectory)

        val deciding = sourceFiles()
            .filter { (path, code) -> path !in KIND_MECHANISM && COMPILED_TIME.containsMatchIn(code) }
            .keys
            .sorted()

        assertEquals("Ask CallableDeclaration.capabilitiesOf instead of reading a compiled definition's time:", "", deciding.joinToString("\n"))
    }

    /** Each source file under [ROOT] by its path relative to it, comments stripped. */
    private fun sourceFiles(): Map<String, String> =
        ROOT.walkTopDown()
            .filter { it.isFile && it.extension in SOURCE_EXTENSIONS }
            .associate { file ->
                file.relativeTo(ROOT).invariantSeparatorsPath to
                    file.readLines()
                        .filterNot { COMMENT_LINE.containsMatchIn(it) }
                        .joinToString("\n") { it.replace(TRAILING_COMMENT, "") }
            }

    /**
     * A form is named by its predicate, by `isForm(call, Form.X)` or `Declared.Source(call, Form.X)`, or - for a clause -
     * by any `definerOf(call)`, since only a clause has a definer.
     */
    private fun enumeratingFiles(): Map<String, List<String>> =
        sourceFiles()
            .filterKeys { it !in FORM_EXEMPT }
            .mapValues { (_, code) ->
                val predicates = PREDICATES.filter { predicate ->
                    predicate.enumerating.containsMatchIn(code) ||
                        (predicate.importedMember.containsMatchIn(code) && predicate.bareCall.containsMatchIn(code)) ||
                        predicate.callsThroughAlias(code)
                }.map { it.name }
                val forms = IS_FORM.findAll(code)
                    .flatMap { FORM_LITERAL.findAll(it.groupValues[1]) }
                    .map { FORM_PREDICATE.getValue(it.groupValues[1]) }
                val definer = if (DEFINER_TEST.containsMatchIn(code)) listOf("clause") else emptyList()

                (predicates + forms + definer).distinct()
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
        // Requires the call's open parenthesis, so a method reference passed as a predicate is not seen, as in
        // `documentation/ElixirDocumentationProvider.kt`.
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
        val FORM_EXEMPT = setOf(
            "psi/$MECHANISM",
            // Stub building may not resolve, and asks only whether a call's stub can hold a modular's children.
            "psi/stub/type/call/Stub.java",
            // Asks whether an occurrence is a clause's own name - a head, not a call - and which `defdelegate` an
            // `as:` atom belongs to; neither enumerates what declares.
            "model/psi/ElixirUsageQueries.kt",
        )
        // The arguments of an `isForm(...)` or `Declared.Source(...)` call, through one level of nested parentheses.
        val IS_FORM = Regex("""(?:isForm|Declared\s*\.\s*Source)\s*\(((?:[^()]|\([^()]*\))*)\)""")
        val FORM_PREDICATE = mapOf(
            "CLAUSE" to "clause",
            "CALLBACK" to "callback",
            "DELEGATION" to "delegation",
            "EXCEPTION" to "exception",
            "EEX_FUNCTION_FROM" to "eex",
            "GENERATOR_EMBED" to "generator",
        )
        val FORM_LITERAL = Regex("""Form\s*\.\s*(${FORM_PREDICATE.keys.joinToString("|")})\b""")
        // Any `definerOf`, called or referenced: only a clause has a definer, however its answer is then tested.
        val DEFINER_TEST = Regex("""\bdefinerOf\b""")
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
