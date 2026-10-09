package org.elixir_lang.model.psi.words

import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.DumbModeTestUtils
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.injection.ElixirSigilInjector
import org.elixir_lang.junit.onPooledThread
import org.elixir_lang.settings.ElixirExperimentalSettings

/** The files and occurrences a search for an atom the literal word search cannot find looks at. */
class DecodedWordQueryTest : PlatformTestCase() {
    private var originalEnableHtmlInjection = false

    override fun setUp() {
        super.setUp()
        originalEnableHtmlInjection = ElixirExperimentalSettings.instance.state.enableHtmlInjection
        ElixirExperimentalSettings.instance.state.enableHtmlInjection = true
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
        InjectedLanguageManager.getInstance(project).registerMultiHostInjector(ElixirSigilInjector(), testRootDisposable)
    }

    override fun tearDown() {
        try {
            ElixirExperimentalSettings.instance.state.enableHtmlInjection = originalEnableHtmlInjection
        } finally {
            super.tearDown()
        }
    }

    /** A name an identifier search can find literally needs only the sigils whose Elixir holds an escape. */
    fun testIdentifierSkipsPlainSigilHosts() {
        val plain = myFixture.addFileToProject("plain.ex", sigil("{M.foo(1)}")).virtualFile
        val escaped = myFixture.addFileToProject("escaped.ex", sigil("{M.\"fo\\x6f\"(1)}")).virtualFile

        assertEquals(listOf(escaped), sigilHosts("foo", injections = true).among(plain, escaped))
    }

    fun testOperatorWalksEverySigilHost() {
        val plain = myFixture.addFileToProject("plain.ex", sigil("{1 <~> 2}")).virtualFile
        val none = myFixture.addFileToProject("none.ex", "defmodule N do\nend\n").virtualFile

        assertEquals(listOf(plain), sigilHosts("<~>", injections = true).among(plain, none))
    }

    /** A sigil the injector fills with EEx holds Elixir as `~H` does; one it fills with a regex does not. */
    fun testEexSigilHostsAreWalked() {
        val files = listOf("E", "L", "r", "S").associateWith {
            myFixture.addFileToProject("$it.ex", sigil("<%= 1 <~> 2 %>", it)).virtualFile
        }
        val walked = sigilHosts("<~>", injections = true).sortedBy { it.name }

        assertEquals(listOf(files.getValue("E"), files.getValue("L")), walked)
    }

    fun testSettingOffSkipsSigilHosts() {
        val host = myFixture.addFileToProject("host.ex", sigil("{1 <~> 2}")).virtualFile

        assertEquals("the setting is on", listOf(host), sigilHosts("<~>", injections = true))

        ElixirExperimentalSettings.instance.state.enableHtmlInjection = false

        assertEquals("the setting is off", emptyList<VirtualFile>(), sigilHosts("<~>", injections = true))
    }

    fun testNonInjectionSiteSkipsSigilHosts() {
        val host = myFixture.addFileToProject("host.ex", sigil("{1 <~> 2}")).virtualFile

        assertEquals("an injecting site", listOf(host), sigilHosts("<~>", injections = true))
        assertEquals("a site without injections", emptyList<VirtualFile>(), sigilHosts("<~>", injections = false))
    }

    fun testDumbModeReadsTheIndex() {
        myFixture.addFileToProject("using.ex", "defmodule C do\n  def c, do: M.\"fo\\x6f\"(1)\nend\n")

        val smart = occurrences("foo")

        assertEquals("one use", 1, smart.size)
        var dumb: List<String>? = null

        DumbModeTestUtils.runInDumbModeSynchronously(project) { dumb = occurrences("foo") }

        assertEquals("in dumb mode", smart, dumb)
    }

    /** A heredoc `~H` injects one fragment over all its lines, which is walked once however many lines it has. */
    fun testFragmentIsWalkedOnce() {
        myFixture.addFileToProject("host.ex", sigil("<p>{a}</p>\n    <p>{b}</p>\n    <p>{M.\"fo\\x6f\"(1)}</p>"))

        assertEquals(1, occurrences("foo", injections = true).size)
    }

    private fun sigil(body: String, name: String = "H") =
        "defmodule C do\n  def render(assigns) do\n    ~$name\"\"\"\n    $body\n    \"\"\"\n  end\nend\n"

    private fun List<VirtualFile>.among(vararg of: VirtualFile): List<VirtualFile> = filter { it in of }

    private fun sigilHosts(atom: String, injections: Boolean): List<VirtualFile> =
        readIndex { DecodedWordQuery.candidateFiles(project, atom, GlobalSearchScope.projectScope(project), injections).sigilHosts.toList() }

    /** The offsets of [atom]'s occurrences in the project. */
    private fun occurrences(atom: String, injections: Boolean = false): List<String> =
        onPooledThread {
            DecodedWordQuery(
                project,
                atom,
                GlobalSearchScope.projectScope(project),
                { leaf, _ -> listOf("${leaf.containingFile.name}:${leaf.textOffset}") },
                injections
            ).findAll().toList()
        }

    private fun <T> readIndex(compute: () -> T): T = onPooledThread { runReadActionBlocking(compute) }
}
