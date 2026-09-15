package org.elixir_lang.psi

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.DebugUtil
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.call.Call
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver

/**
 * A line that does not parse inside a `do` block is an error of its own: the block still ends at its `end`, and the
 * definitions after it stay in the module, where structure view, navigation, folding and inspections find them.
 */
class DoBlockRecoveryTest : PlatformTestCase() {
    private var files = 0

    override fun tearDown() {
        try {
            ElixirLanguageLevelResolver.overrideLanguageLevel(project, null)
        } finally {
            super.tearDown()
        }
    }

    fun testUnclosedList() = assertRecovered(inDefinition("[1, 2"))
    fun testUnclosedTuple() = assertRecovered(inDefinition("{1, 2"))
    fun testUnclosedMap() = assertRecovered(inDefinition("%{a: 1"))
    fun testUnclosedBitstring() = assertRecovered(inDefinition("<<1, 2"))
    fun testUnclosedStruct() = assertRecovered(inDefinition("%A{a: 1"))
    fun testUnclosedParenthesizedExpression() = assertRecovered(inDefinition("(1 + 2"))
    fun testKeywordWithoutValueInCall() = assertRecovered(inDefinition("foo(a: )"))
    fun testKeywordKeyWithoutValueInList() = assertRecovered(inDefinition("[a:"))
    fun testMapAssociationWithoutValue() = assertRecovered(inDefinition("%{a =>"))
    fun testStrayClosingParenthesis() = assertRecovered(inDefinition("1)"))
    fun testStrayClosingBracket() = assertRecovered(inDefinition("1]"))
    fun testStrayClosingBrace() = assertRecovered(inDefinition("1}"))

    fun testDoKeywordWithoutValue() = assertRecovered(
        """
        defmodule A do
          def f, do: )
          def g, do: 2
        end
        """.trimIndent(),
        keepsDefinitionF = false
    )

    fun testMalformedLineAfterAValidOne() = assertRecovered(
        """
        defmodule A do
          def f do
            x = 1
            [x, 2
          end
          def g, do: 2
        end
        """.trimIndent()
    )

    fun testMalformedLineBeforeRescue() {
        val source = """
            defmodule A do
              def f do
                [1, 2
              rescue
                _ -> :error
              end
              def g, do: 2
            end
            """.trimIndent()

        assertRecovered(source)

        for (languageLevel in DIALECTS) {
            val file = parse(source, languageLevel)
            val definitionF = items(file)!!.single { it.text.startsWith("def f do") }

            assertNotNull(
                "`def f` keeps its `rescue` on $languageLevel:\n${DebugUtil.psiToString(file, true)}",
                PsiTreeUtil.findChildOfType(definitionF, ElixirBlockList::class.java)
            )
        }
    }

    fun testMalformedLineInRescue() = assertRecovered(
        """
        defmodule A do
          def f do
            1
          rescue
            [1, 2
          end
          def g, do: 2
        end
        """.trimIndent()
    )

    // Already recovered: the malformed expression ends before the inner `end`.

    fun testUnclosedParenthesesCallAlreadyRecovers() =
        assertUnchanged(inDefinition("foo(1, 2"), definitionGInModule = true, errorOffsets = listOf(38))

    fun testDanglingOperatorsAlreadyRecover() {
        assertUnchanged(inDefinition("1 +"), definitionGInModule = true, errorOffsets = listOf(33))
        assertUnchanged(inDefinition("x |>"), definitionGInModule = true, errorOffsets = listOf(34))
        assertUnchanged(inDefinition("x ="), definitionGInModule = true, errorOffsets = listOf(33))
        assertUnchanged(inDefinition("x when"), definitionGInModule = true, errorOffsets = listOf(36))
        assertUnchanged(inDefinition("x ::"), definitionGInModule = true, errorOffsets = listOf(34))
    }

    fun testTrailingCommaInCallAlreadyRecovers() =
        assertUnchanged(inDefinition("foo(1,"), definitionGInModule = true, errorOffsets = listOf(36))

    // Elixir reads these the same way: the construct runs to the end of the file, or the extra `end` closes the module.

    fun testUnterminatedBlocksRunToTheEndOfTheFile() {
        assertUnchanged(inDefinition("fn x -> x"), definitionGInModule = false, errorOffsets = listOf(64))
        assertUnchanged(
            """
            defmodule A do
              def f do
                case x do
                  1 -> 1
              end
              def g, do: 2
            end
            """.trimIndent(),
            definitionGInModule = false,
            errorOffsets = listOf(77)
        )
        assertUnchanged(
            """
            defmodule A do
              def f do
                if x do
                  1
              end
              def g, do: 2
            end
            """.trimIndent(),
            definitionGInModule = false,
            errorOffsets = listOf(70)
        )
    }

    fun testUnterminatedQuotesRunToTheEndOfTheFile() {
        assertUnchanged(inDefinition("\"abc"), definitionGInModule = false, errorOffsets = listOf(59))
        assertUnchanged(inDefinition("~s(abc"), definitionGInModule = false, errorOffsets = listOf(61))
    }

    fun testExtraEndClosesTheModule() = assertUnchanged(
        """
        defmodule A do
          def f do
            1
          end
          end
          def g, do: 2
        end
        """.trimIndent(),
        definitionGInModule = false,
        errorOffsets = listOf(59)
    )

    private fun assertRecovered(source: String, keepsDefinitionF: Boolean = true) {
        for (languageLevel in DIALECTS) {
            val file = parse(source, languageLevel)
            val tree = DebugUtil.psiToString(file, true)
            val definitionG = source.indexOf("def g")

            assertEquals(
                "The module is the only call of the file on $languageLevel:\n$tree",
                listOf(source),
                file.children.filterIsInstance<Call>().map { it.text }
            )

            val items = items(file)!!.map { it.text }

            assertTrue(
                "`def g` is an expression of the module's `do` block on $languageLevel:\n$tree",
                "def g, do: 2" in items
            )

            if (keepsDefinitionF) {
                assertTrue(
                    "`def f` is an expression of the module's `do` block ending at its own `end` " +
                        "on $languageLevel:\n$tree",
                    items.any { it.startsWith("def f do") && it.endsWith("end") }
                )
            }

            val errors = errors(file)

            assertFalse("An error is reported on $languageLevel:\n$tree", errors.isEmpty())

            for (error in errors) {
                assertTrue(
                    "'${error.errorDescription}' at ${error.textRange} is before `def g` ($definitionG) " +
                        "on $languageLevel:\n$tree",
                    error.textRange.startOffset < definitionG && error.textRange.endOffset <= definitionG
                )
            }
        }
    }

    private fun assertUnchanged(source: String, definitionGInModule: Boolean, errorOffsets: List<Int>) {
        for (languageLevel in DIALECTS) {
            val file = parse(source, languageLevel)
            val tree = DebugUtil.psiToString(file, true)

            assertEquals(
                "Whether `def g` is an expression of the module's `do` block on $languageLevel:\n$tree",
                definitionGInModule,
                items(file)?.any { it.text == "def g, do: 2" } == true
            )
            assertEquals(
                "Parse error offsets on $languageLevel:\n$tree",
                errorOffsets,
                errors(file).map { it.textRange.startOffset }
            )
        }
    }

    private fun inDefinition(line: String): String = """
        defmodule A do
          def f do
            $line
          end
          def g, do: 2
        end
        """.trimIndent()

    private fun parse(source: String, languageLevel: ElixirLanguageLevel): PsiFile {
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, languageLevel)

        return myFixture.configureByText("recovery_${files++}.ex", source)
    }

    private fun items(file: PsiFile): List<Call>? =
        file.children.filterIsInstance<Call>().firstOrNull()
            ?.let { PsiTreeUtil.getChildOfType(it, ElixirDoBlock::class.java) }
            ?.stab
            ?.stabBody
            ?.children
            ?.filterIsInstance<Call>()

    private fun errors(file: PsiFile): List<PsiErrorElement> =
        PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java).sortedBy { it.textRange.startOffset }

    private companion object {
        val DIALECTS = listOf(ElixirLanguageLevel.V1_11, ElixirLanguageLevel.V1_20)
    }
}
