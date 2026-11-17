package org.elixir_lang.lowering

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiElement
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.parser_definition.ParsingTestCase
import org.elixir_lang.psi.ElixirFile

/** Lowers snippets at chosen language levels, so each leg's metadata is checked whichever Elixir runs the tests. */
abstract class LoweringTestCase : ParsingTestCase() {
    /** The lowering of [code], or of the element [select] picks in it, at [version], an Elixir version or `<elixir>/<otp>`. */
    protected fun lower(code: String, version: String = NEWEST, select: (ElixirFile) -> PsiElement = { it }): ElixirAst {
        val file = createPsiFile(getTestName(false), code) as ElixirFile
        val elixirVersion = version.substringBefore('/')
        val otpVersion = version.substringAfter('/', "").ifEmpty { null }

        return ReadAction.computeBlocking<ElixirAst, Throwable> {
            Lowering.of(file, ElixirLanguageLevel.of(elixirVersion, otpVersion)).lower(select(file))
        }
    }

    /** Whether [lowered] is broken code: it has placeholders, and every one is an error. */
    protected fun isBroken(lowered: ElixirAst): Boolean {
        val reasons = placeholderReasons(lowered)

        return reasons.isNotEmpty() && reasons.all { it == ElixirAst.Placeholder.Reason.Error }
    }

    /** [code] lowers to broken code at [elixirVersion]; a failure names the placeholders' reasons. */
    protected fun assertBroken(code: String, elixirVersion: String = NEWEST) {
        val lowered = lower(code, elixirVersion)

        assertTrue("$code: ${placeholderReasons(lowered)}", isBroken(lowered))
    }

    protected fun placeholderReasons(ast: ElixirAst): List<ElixirAst.Placeholder.Reason> = ast.placeholders().map { it.reason }

    /**
     * [code] lowers, at each version in [expected], to the paired term, printed by [inspect] with columns and token
     * metadata. A version is an Elixir version or `<elixir>/<otp>`.
     */
    protected fun assertLowers(code: String, vararg expected: Pair<String, String>) =
        assertEquals(
            expected.joinToString("\n") { (version, term) -> "$version: $term" },
            expected.joinToString("\n") { (version, _) ->
                "$version: " + inspect(lower(code, version).toOtp(COLUMNS_AND_TOKEN_METADATA))
            }
        )

    /** [code] lowers to [expected] on the oldest and newest supported versions. */
    protected fun assertLowers(code: String, expected: String) = assertLowers(code, OLDEST to expected, NEWEST to expected)

    companion object {
        val COLUMNS_AND_TOKEN_METADATA = ParserOptions(columns = true, tokenMetadata = true)

        const val OLDEST = "1.11.4"
        val NEWEST: String = ElixirLanguageLevel.FALLBACK.elixirVersion

        /** The placeholder reasons of code that lowers to one error. */
        val ERROR = listOf(ElixirAst.Placeholder.Reason.Error)
    }
}
