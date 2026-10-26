package org.elixir_lang.mix

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import org.elixir_lang.PlatformTestCase

/**
 * Tests for [Test] - the Mix-level detection of test files, including reading `:test_load_filters` from the
 * governing `mix.exs` and falling back to the default `_test.exs` suffix.
 */
class MixTestFileDetectionTest : PlatformTestCase() {
    fun testDefaultSuffixWhenNoLoadFilters() {
        mixExs("default", project = "[app: :default]")

        assertTrue(isTestFile("default/test/foo_test.exs"))
        assertFalse(isTestFile("default/test/foo.exs"))
        assertFalse(isTestFile("default/lib/foo.ex"))
    }

    fun testCustomSuffixFromLoadFilters() {
        mixExs("custom", project = """[app: :custom, test_load_filters: [&String.ends_with?(&1, "_spec.exs")]]""")

        assertTrue(isTestFile("custom/test/foo_spec.exs"))
        assertFalse("custom suffix replaces the default", isTestFile("custom/test/foo_test.exs"))
    }

    fun testCustomSuffixFromKeywordDoProject() {
        myFixture.tempDirFixture.createFile(
            "keyword_do/mix.exs",
            """
            |defmodule Sample.MixProject do
            |  use Mix.Project
            |
            |  def project, do: [app: :keyword_do, test_load_filters: [&String.ends_with?(&1, "_spec.exs")]]
            |end
            """.trimMargin()
        )

        assertTrue(isTestFile("keyword_do/test/foo_spec.exs"))
        assertFalse(isTestFile("keyword_do/test/foo_test.exs"))
    }

    fun testCustomSuffixFromTheReturnedKeywordList() {
        mixExs(
            "returned",
            project = """
                |[app: :discarded]
                |    [app: :returned, test_load_filters: [&String.ends_with?(&1, "_spec.exs")]]
            """.trimMargin()
        )

        assertTrue("project/0 returns its last expression", isTestFile("returned/test/foo_spec.exs"))
        assertFalse(isTestFile("returned/test/foo_test.exs"))
    }

    fun testMultipleLoadFilters() {
        mixExs(
            "multi",
            project = """[
              |  app: :multi,
              |  test_load_filters: [&String.ends_with?(&1, "_test.exs"), &String.ends_with?(&1, "_spec.exs")]
              |]""".trimMargin()
        )

        assertTrue(isTestFile("multi/test/foo_test.exs"))
        assertTrue(isTestFile("multi/test/foo_spec.exs"))
        assertFalse(isTestFile("multi/test/foo.exs"))
    }

    fun testUnanalyzableRegexFiltersFallBackToDefault() {
        mixExs("regex", project = """[app: :regex, test_load_filters: [~r/_test\.exs$/]]""")

        assertTrue("an unanalyzable regex filter must not narrow detection", isTestFile("regex/test/foo_test.exs"))
    }

    fun testUnanalyzableFunctionFiltersFallBackToDefault() {
        mixExs("fn", project = """[app: :fn, test_load_filters: [&MyHelper.filter/1]]""")

        assertTrue(isTestFile("fn/test/foo_test.exs"))
    }

    fun testNoGoverningMixExsFallsBackToDefault() {
        myFixture.tempDirFixture.createFile("orphan/test/foo_test.exs", "")

        assertTrue(isTestFile("orphan/test/foo_test.exs"))
        assertFalse(isTestFile("orphan/test/foo.exs"))
    }

    fun testUmbrellaResolvesAgainstChildApp() {
        mixExs("umbrella/apps/app_a", project = """[app: :app_a, test_load_filters: [&String.ends_with?(&1, "_spec.exs")]]""")
        mixExs("umbrella/apps/app_b", project = "[app: :app_b]")

        assertTrue(isTestFile("umbrella/apps/app_a/test/a_spec.exs"))
        assertFalse("app_a's custom filter must not leak to its own _test.exs", isTestFile("umbrella/apps/app_a/test/a_test.exs"))
        assertTrue("app_b inherits nothing from app_a and uses the default", isTestFile("umbrella/apps/app_b/test/b_test.exs"))
    }

    fun testReResolvesAfterMixExsEdit() {
        val mixExs = mixExs("editable", project = "[app: :editable]")

        assertTrue("default suffix before edit", isTestFile("editable/test/foo_test.exs"))
        assertFalse("custom suffix not yet present", isTestFile("editable/test/foo_spec.exs"))

        WriteCommandAction.runWriteCommandAction(project) {
            val document = FileDocumentManager.getInstance().getDocument(mixExs)!!
            document.setText(mixProjectText("""[app: :editable, test_load_filters: [&String.ends_with?(&1, "_spec.exs")]]"""))
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }

        assertTrue("cache invalidates so the new custom suffix applies", isTestFile("editable/test/foo_spec.exs"))
        assertFalse("default no longer applies after the edit", isTestFile("editable/test/foo_test.exs"))
    }

    private fun isTestFile(path: String): Boolean = Test.isTestFile(psiFile(path))

    private fun psiFile(path: String): PsiFile {
        val virtualFile = myFixture.tempDirFixture.getFile(path)
            ?: myFixture.tempDirFixture.createFile(path, "")

        return PsiManager.getInstance(project).findFile(virtualFile)!!
    }

    /** Creates `<dir>/mix.exs` with a `project/0` returning [project], and returns the created [VirtualFile]. */
    private fun mixExs(dir: String, project: String): VirtualFile =
        myFixture.tempDirFixture.createFile("$dir/mix.exs", mixProjectText(project))

    private fun mixProjectText(project: String): String =
        """
        |defmodule Sample.MixProject do
        |  use Mix.Project
        |
        |  def project do
        |    $project
        |  end
        |end
        """.trimMargin()
}
