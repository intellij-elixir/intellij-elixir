package org.elixir_lang.lowering

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.language_level.ElixirLanguageFeature.NORMALIZED_IDENTIFIERS
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.parser_definition.ParsingTestCase
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.functionNameAtomValue
import org.elixir_lang.psi.impl.headAtomValue
import org.elixir_lang.psi.impl.quotedAtomValue
import java.io.File

/**
 * Names the corpus lacks: a decomposed `café` as a call and as a `def`, normalized from 1.14 and a syntax error before,
 * and an atom over the 255 code points an atom can hold.
 */
class QuotedAtomFixturesTest : ParsingTestCase() {
    fun testDecomposedCallName() {
        val file = fixture("DecomposedCall")

        ReadAction.computeBlocking<Unit, Throwable> {
            val call = PsiTreeUtil.findChildOfType(file, Call::class.java)!!
            assertEquals(cafe(file), functionNameAtomValue(call))
        }
    }

    fun testDecomposedDefName() {
        val file = fixture("DecomposedDef")

        ReadAction.computeBlocking<Unit, Throwable> {
            val def = PsiTreeUtil.findChildrenOfType(file, Call::class.java).single { CallDefinitionClause.`is`(it) }
            assertEquals(cafe(file), headAtomValue(CallDefinitionClause.head(def)!!))
        }
    }

    fun testLongQuotedAtomHasNoName() {
        val file = fixture("LongQuotedAtom")

        ReadAction.computeBlocking<Unit, Throwable> {
            val atom = PsiTreeUtil.findChildOfType(file, ElixirAtom::class.java)!!
            assertNull(ElementLowering.atomName(atom))
            assertNull(quotedAtomValue(atom))
        }
    }

    private fun fixture(name: String): PsiFile =
        createPsiFile(name, FileUtil.loadFile(File("$DIRECTORY/$name.ex"), Charsets.UTF_8.name(), true).trim())

    /** `café` as the fixtures spell it, with a combining acute accent, unless the level normalizes it. */
    private fun cafe(file: PsiFile): String =
        if (NORMALIZED_IDENTIFIERS.isSufficient(ElixirLanguageLevelResolver.languageLevelFor(file))) "café" else "café"

    private companion object {
        const val DIRECTORY = "testData/org/elixir_lang/parser_definition/name_spelling_parsing_test_case"
    }
}
