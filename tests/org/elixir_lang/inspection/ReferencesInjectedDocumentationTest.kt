package org.elixir_lang.inspection

import org.elixir_lang.PlatformTestCase
import java.nio.file.Files
import java.nio.file.Path

/**
 * `References` must not report a problem inside a documentation-injected code sample: the platform logs
 * `Cannot restore <element> from injected` when a smart pointer into injected PSI fails to round-trip,
 * and a logged error fails the test on its own - the same failure class `DocumentationCodeBlockHighlightingTest`
 * guards for annotators, now checked against a real inspection now that `References` is enabled by
 * default. Real corpus files, not synthetic fixtures, because the failure only showed up against real
 * `@doc`/`@moduledoc` examples.
 */
class ReferencesInjectedDocumentationTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(References::class.java)
    }

    private fun highlightCorpusFile(relativePath: String) {
        val corpus = System.getenv(CORPUS_ENVIRONMENT_VARIABLE)

        assertNotNull(
            "$CORPUS_ENVIRONMENT_VARIABLE is not set. The Gradle test task sets it when " +
                    ".github/ci-versions.json declares a corpus for Elixir ${System.getenv("ELIXIR_VERSION")}",
            corpus
        )

        val elixir = Files.list(Path.of(corpus).resolve("elixir-lang")).use { it.findFirst() }
        assertTrue("No elixir-lang checkout under $corpus", elixir.isPresent)

        val path = elixir.get().resolve(relativePath)
        assertTrue("$path is missing from the corpus", Files.isRegularFile(path))

        myFixture.configureByText(path.fileName.toString(), Files.readString(path))
        myFixture.doHighlighting()
    }

    fun testAccess() = highlightCorpusFile("lib/elixir/lib/access.ex")
    fun testMixTest() = highlightCorpusFile("lib/mix/test/mix_test.exs")
    fun testString() = highlightCorpusFile("lib/elixir/lib/string.ex")

    companion object {
        private const val CORPUS_ENVIRONMENT_VARIABLE = "ELIXIR_PARSING_CORPUS"
    }
}
