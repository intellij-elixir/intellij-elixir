package org.elixir_lang.model.psi.config

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.expander.LegSdk
import java.io.File

/** Completion of the apps and keys of the application env at a read's and a `config` call's arguments. */
class ConfigKeyCompletionTest : PlatformTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/model/psi/config"

    override fun setUp() {
        super.setUp()
        disableMixDepsCheck(testRootDisposable)
        LegSdk.setUp(module, testRootDisposable)
        myFixture.copyDirectoryToProject("completion", "")
    }

    override fun tearDown() {
        try {
            LegSdk.tearDown(module)
        } finally {
            super.tearDown()
        }
    }

    fun testReadAppOffersOwnConfiguredAndReadApps() {
        typing(LIB, "def f, do: Application.get_env(:app_<caret>)")

        assertContainsElements(candidates(), "app_own", "app_configured", "app_read")
        assertEquals(module("def f, do: Application.get_env(:app_configured)"), choose("app_configured"))
    }

    fun testReadKeyOffersSetAndReadKeysOfTheApp() {
        typing(LIB, "def f, do: Application.get_env(:my_app, :re<caret>)")

        val candidates = candidates()
        assertContainsElements(candidates, "retries", "request_timeout", "region")
        assertDoesntContain(candidates, "reconnect", "refresh", "re")
        assertEquals(module("def f, do: Application.get_env(:my_app, :request_timeout)"), choose("request_timeout"))
    }

    fun testKeyBeingWrittenMidNameIsNotOfferedBack() {
        typing(LIB, "def f, do: Application.get_env(:my_app, :re<caret>gionx)")

        val candidates = candidates()
        assertContainsElements(candidates, "region")
        assertDoesntContain(candidates, "regionx")
        assertEquals(module("def f, do: Application.get_env(:my_app, :regionx)"), choose(null))
    }

    fun testQuotedKeyBeingWrittenBeforeAnEscapeIsNotOfferedBack() {
        typing(LIB, "def f, do: Application.get_env(:my_app, :\"re<caret>gion\\\"x\")")

        val candidates = candidates()
        assertContainsElements(candidates, "region")
        assertDoesntContain(candidates, "region\"x")
        assertEquals(module("def f, do: Application.get_env(:my_app, :\"region\\\"x\")"), choose(null))
    }

    fun testReadKeyOffersALibraryReadKey() {
        myFixture.withSourceLibrary("library_app", File(testDataPath, "completion_library")) {
            typing(LIB, "def f, do: Application.get_env(:library_app, :buf<caret>)")

            assertEquals(module("def f, do: Application.get_env(:library_app, :buffer_size)"), choose("buffer_size"))
        }
    }

    fun testCompileEnvKeyPathHeadOffersKeys() {
        typing(LIB, "@retries Application.compile_env(:my_app, [:ret<caret>])")

        assertEquals(module("@retries Application.compile_env(:my_app, [:retries])"), choose("retries"))
    }

    fun testConfigAppOffersApps() {
        typing(CONFIG, "import Config\n\nconfig :app_<caret>\n")

        assertContainsElements(candidates(), "app_own", "app_configured", "app_read")
        assertEquals("import Config\n\nconfig :app_read\n", choose("app_read"))
    }

    fun testConfig2KeyInsertsAKeyword() {
        typing(CONFIG, "import Config\n\nconfig :my_app, ret<caret>\n")

        assertEquals("import Config\n\nconfig :my_app, retries: \n", choose("retries"))
    }

    fun testConfig2KeyAfterAKeyOnTheSameLineOffersTheOtherKeys() {
        typing(CONFIG, "import Config\n\nconfig :my_app, retries: 3, re<caret>\n")

        val candidates = candidates()
        assertContainsElements(candidates, "request_timeout", "region")
        assertDoesntContain(candidates, "retries")
        assertEquals("import Config\n\nconfig :my_app, retries: 3, region: \n", choose("region"))
    }

    fun testConfig2KeyOnTheNextLineOfAListOffersTheOtherKeys() {
        typing(CONFIG, "import Config\n\nconfig :my_app,\n  retries: 3,\n  re<caret>\n")

        val candidates = candidates()
        assertContainsElements(candidates, "request_timeout", "region")
        assertDoesntContain(candidates, "retries")
        assertEquals("import Config\n\nconfig :my_app,\n  retries: 3,\n  region: \n", choose("region"))
    }

    fun testConfig2KeyAfterANestedKeywordValueOffersTheOtherKeys() {
        typing(CONFIG, "import Config\n\nconfig :my_app, region: [primary: :eu], re<caret>\n")

        val candidates = candidates()
        assertContainsElements(candidates, "retries", "request_timeout")
        assertDoesntContain(candidates, "region")
        assertEquals("import Config\n\nconfig :my_app, region: [primary: :eu], retries: \n", choose("retries"))
    }

    fun testConfig2KeyInABracketedListInsertsAKeyword() {
        typing(CONFIG, "import Config\n\nconfig :my_app, [ret<caret>]\n")

        assertEquals("import Config\n\nconfig :my_app, [retries: ]\n", choose("retries"))
    }

    fun testConfig2KeyBeforeTheKeysOfABracketedListOffersTheOtherKeys() {
        typing(CONFIG, "import Config\n\nconfig :my_app, [re<caret>, retries: 3]\n")

        val candidates = candidates()
        assertContainsElements(candidates, "request_timeout", "region")
        assertDoesntContain(candidates, "retries")
        assertEquals("import Config\n\nconfig :my_app, [region: , retries: 3]\n", choose("region"))
    }

    fun testConfig2KeyRenamedBeforeItsColonKeepsTheValue() {
        typing(CONFIG, "import Config\n\nconfig :my_app, ret<caret>: 3\n")

        assertEquals("import Config\n\nconfig :my_app, retries: 3\n", choose("retries"))
    }

    fun testConfig2KeyRenamedInABracketedListKeepsTheValue() {
        typing(CONFIG, "import Config\n\nconfig :my_app, [ret<caret>: 3]\n")

        assertEquals("import Config\n\nconfig :my_app, [retries: 3]\n", choose("retries"))
    }

    fun testConfig3KeyInsertsAnAtom() {
        typing(CONFIG, "import Config\n\nconfig :my_app, :ret<caret>\n")

        assertEquals("import Config\n\nconfig :my_app, :retries\n", choose("retries"))
    }

    fun testConfig3BareKeyInsertsAnAtom() {
        typing(CONFIG, "import Config\n\nconfig(:my_app, ret<caret>, 1)\n")

        assertEquals("import Config\n\nconfig(:my_app, :retries, 1)\n", choose("retries"))
    }

    fun testModuleNameKeysAreNotOffered() {
        typing(LIB, "def f, do: Application.get_env(:my_app, :\"Elixir.MyApp.<caret>\")")

        val candidates = candidates()
        val text = choose(null)
        for (module in listOf("MyApp.Cache", "MyApp.Repo", "MyApp.Store")) {
            assertFalse("$module offered in $candidates", candidates.any { it.contains(module) })
            assertFalse("$module inserted: $text", text.contains(module))
        }
    }

    fun testKeyNeedingQuotesIsInsertedQuoted() {
        typing(LIB, "def f, do: Application.get_env(:my_app, :nee<caret>)")

        assertEquals(module("def f, do: Application.get_env(:my_app, :\"needs-quotes\")"), choose("needs-quotes"))
    }

    fun testQuotedKeyAfterAnEscapeOffersNoKeyMatchingOnlyTheLastPart() {
        typing(LIB, "def f, do: Application.get_env(:my_app, :\"say\\\"qu<caret>\")")

        assertDoesntContain(candidates(), "queue", "say\"qu")
        assertEquals(module("def f, do: Application.get_env(:my_app, :\"say\\\"qu\")"), choose(null))
    }

    fun testNonLiteralAppOffersNoKeys() {
        typing(LIB, "def f(app), do: Application.get_env(app, :ret<caret>)")

        assertDoesntContain(candidates(), "retries")
        assertEquals(module("def f(app), do: Application.get_env(app, :ret)"), choose(null))
    }

    fun testAnotherModulesGetEnvOffersNoKeys() {
        typing(LIB, "def f, do: MyApp.Fake.get_env(:my_app, :ret<caret>)")

        assertDoesntContain(candidates(), "retries")
        assertEquals(module("def f, do: MyApp.Fake.get_env(:my_app, :ret)"), choose(null))
    }

    fun testApplicationAliasedAwayOffersNoKeys() {
        typing(LIB, "alias MyApp.Fake, as: Application\n  def f, do: Application.get_env(:my_app, :ret<caret>)")

        assertDoesntContain(candidates(), "retries")
        assertEquals(
            module("alias MyApp.Fake, as: Application\n  def f, do: Application.get_env(:my_app, :ret)"),
            choose(null)
        )
    }

    fun testKeyOnlyAnotherModuleReadsIsNotOffered() {
        typing(LIB, "def f, do: Application.get_env(:my_app, :fake<caret>)")

        assertDoesntContain(candidates(), "fake_read")
        assertEquals(module("def f, do: Application.get_env(:my_app, :fake)"), choose(null))
    }

    fun testAppOnlyAnotherModuleReadsIsNotOffered() {
        typing(LIB, "def f, do: Application.get_env(:app_<caret>)")

        val candidates = candidates()
        assertContainsElements(candidates, "app_read")
        assertDoesntContain(candidates, "app_fake")
    }

    fun testInterpolatedKeyOffersNoKeys() {
        typing(LIB, "def f(x), do: Application.get_env(:my_app, :\"#{x}ret<caret>\")")

        assertDoesntContain(candidates(), "retries")
        assertEquals(module("def f(x), do: Application.get_env(:my_app, :\"#{x}ret\")"), choose(null))
    }

    /** Opens a new file at [path] holding [text], with the caret at its `<caret>`; a [LIB] file's text is a module body. */
    private fun typing(path: String, text: String) {
        val contents = if (path == LIB) module(text) else text
        val caret = contents.indexOf(CARET)
        val file = myFixture.addFileToProject(path, contents.replace(CARET, ""))
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(caret)
    }

    private var completed = false
    private var offered: Array<LookupElement>? = null

    /** Completes at the caret once: the items the lookup offers, or `null` when none opened. */
    private fun complete(): Array<LookupElement>? {
        if (!completed) {
            offered = myFixture.completeBasic()
            completed = true
        }

        return offered
    }

    /** The lookup strings offered at the caret, none when no lookup opened; the lookup is left open. */
    private fun candidates(): List<String> = complete()?.map { it.lookupString }.orEmpty()

    /**
     * The document after completing at the caret and choosing [lookupString] if a lookup opens, or closing it unchosen
     * when [lookupString] is `null`.
     */
    private fun choose(lookupString: String?): String {
        val items = complete()

        if (items != null) {
            if (lookupString == null) {
                myFixture.lookup?.hideLookup(true)
            } else {
                myFixture.lookup.currentItem = items.firstOrNull { it.lookupString == lookupString }
                    ?: throw AssertionError("`$lookupString` not offered: ${items.map { it.lookupString }}")
                myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
            }
        }

        return myFixture.editor.document.text
    }

    private fun module(body: String): String = "defmodule MyApp.Typing do\n  $body\nend\n"

    private companion object {
        const val CARET = "<caret>"
        const val LIB = "lib/my_app/typing.ex"
        const val CONFIG = "config/typing.exs"
    }
}
