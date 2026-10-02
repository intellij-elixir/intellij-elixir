package org.elixir_lang.parser_definition

import com.intellij.lang.LanguageASTFactory
import junit.framework.Test
import junit.framework.TestSuite
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.eex.file.EexDataOuterLanguageElementTest
import org.elixir_lang.snapshot.ResolveUsagesAgreementTest

/**
 * A parsing test's mock application has none of the plugin's `lang.ast.factory` registrations, so the factory it finds
 * for Elixir must not stay cached for the tests that run after it in the same JVM. The suite fixes that order, which
 * Gradle otherwise varies between runs.
 */
class ParsingTestCaseLeakTest : ParsingTestCase() {
    fun testParseOnTheMockApplication() {
        // Resolves under the mock application even if an earlier test cached the plugin's factory.
        LanguageASTFactory.INSTANCE.clearCache(ElixirLanguage)
        createPsiFile(getTestName(false), "@1").node.getChildren(null)
    }

    companion object {
        @JvmStatic
        fun suite(): Test = TestSuite(ParsingTestCaseLeakTest::class.java.name).apply {
            addTest(TestSuite.createTest(ParsingTestCaseLeakTest::class.java, "testParseOnTheMockApplication"))
            addTest(TestSuite.createTest(ResolveUsagesAgreementTest::class.java, "testInputs"))
            addTest(
                TestSuite.createTest(
                    EexDataOuterLanguageElementTest::class.java,
                    "testEexDataLeavesAreOuterLanguageElementsInAnHtmlEexFile"
                )
            )
        }
    }
}
