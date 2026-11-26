package org.elixir_lang

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.TestLoggerFactory
import org.elixir_lang.junit.LightTestCase
import org.elixir_lang.junit.logs.GuardedLoggedErrorProcessor
import org.junit.Rule
import java.nio.file.Path

abstract class PlatformTestCase : LightTestCase() {

    @Rule
    @JvmField
    val testWatcher = TestLoggerFactory.createTestWatcher()

    @Throws(Exception::class)
    override fun setUp() {
        super.setUp()

        // Allow access to testData directory in tests
        val testDataPath = Path.of(myFixture.testDataPath).toAbsolutePath().toString()
        VfsRootAccess.allowRootAccess(myFixture.testRootDisposable, testDataPath)
    }

    @Throws(Exception::class)
    override fun tearDown() {
            super.tearDown()
    }

    /**
     * Executes code that may log a warning from [category], capturing and returning the last one. Warnings from any
     * other logger still reach [org.elixir_lang.junit.logs.UnexpectedLogs].
     *
     * @param category The logger category to monitor (e.g., "org.elixir_lang.sdk.erlang.Type")
     * @param block The code to execute that will log the warning
     * @return Pair of (result from block, captured warning message or null)
     */
    protected fun <T> captureLoggedWarning(category: String, block: () -> T): Pair<T, String?> {
        val captured = mutableListOf<String>()
        val watched = category

        val processor = object : GuardedLoggedErrorProcessor() {
            override fun processWarn(category: String, message: String, t: Throwable?): Boolean =
                // TestLoggerFactory prefixes categories with '#'
                if (category.removePrefix("#") == watched) {
                    captured += message
                    false
                } else {
                    super.processWarn(category, message, t)
                }
        }

        val result = LoggedErrorProcessor.executeWith(processor).use { block() }

        return Pair(result, captured.lastOrNull())
    }

    /**
     * One error passed to [LoggedErrorProcessor.processError], with the `#` that `TestLoggerFactory`
     * prefixes onto logger names already stripped from [category].
     *
     * [org.elixir_lang.errorreport.Logger] puts its own title in the [Throwable] and a PSI excerpt in
     * the log message, so a test asserting on what *the plugin* reported wants [title], while one
     * asserting on what a platform logger reported wants [message].
     */
    protected data class LoggedError(val category: String, val message: String, val title: String?)

    /**
     * Executes code that may log errors, capturing every one of them.
     *
     * Everything is captured and the caller filters, because the three things worth keying on differ
     * per test - an exact category, a partial one, or the [LoggedError.title] of an error raised
     * through [org.elixir_lang.errorreport.Logger]. Baking any one of those into the helper would
     * leave the other two writing their own [LoggedErrorProcessor].
     *
     * @param suppress whether to swallow what is captured. Pass `false` where any logged error should
     *   still fail the test, and the captured list only sharpens the message.
     * @param block The code to execute
     * @return Pair of (result from block, errors in the order they were logged)
     */
    protected fun <T> captureLoggedErrors(
        suppress: Boolean = true,
        block: () -> T
    ): Pair<T, kotlin.collections.List<LoggedError>> {
        val captured = mutableListOf<LoggedError>()

        val processor = object : GuardedLoggedErrorProcessor() {
            override fun processError(
                category: String,
                message: String,
                details: Array<out String>,
                t: Throwable?
            ): Set<Action> {
                captured.add(LoggedError(category.removePrefix("#"), message, t?.message))

                return if (suppress) Action.NONE else super.processError(category, message, details, t)
            }
        }

        val result = LoggedErrorProcessor.executeWith(processor).use { block() }

        return Pair(result, captured.toList())
    }

    /**
     * Every file injected into [file], driven through `enumerateEx` on every element and then by highlighting; fails,
     * naming [subject], on any error logged on the way. [file] defaults to the top-level file: with the caret inside an
     * injection, [myFixture]'s own file may be the injected one.
     */
    protected fun enumeratedInjections(
        file: PsiFile = InjectedLanguageManager.getInstance(project).getTopLevelFile(myFixture.file),
        subject: String = "the file"
    ): kotlin.collections.List<PsiFile> {
        val manager = InjectedLanguageManager.getInstance(project)
        val injected = LinkedHashSet<PsiFile>()

        val (_, loggedErrors) = captureLoggedErrors {
            PsiTreeUtil.collectElements(file) { true }.forEach { element ->
                manager.enumerateEx(element, file, true) { injectedPsi, _ -> injected.add(injectedPsi) }
            }
            myFixture.doHighlighting()
        }

        assertEmpty("$subject logged errors: ${loggedErrors.map { it.message }}", loggedErrors)

        return injected.toList()
    }

    /**
     * Typing one space, and then two, at the caret of a fresh file holding [source] logs no error, and leaves each of the
     * [injected] files' documents equal to its parsed text: the platform widens a place over a character typed at its
     * start, which Elixir may trim as indentation.
     */
    protected fun assertTypingWhitespaceKeepsInjectedDocumentsWhole(source: String, injected: () -> kotlin.collections.List<PsiFile>) {
        val documents = PsiDocumentManager.getInstance(project)
        val problems = listOf(" ", "  ").flatMap { typed ->
            myFixture.configureByText("typed${typed.length}.ex", source)
            assertFalse("there is no injected file whose document could be compared", injected().isEmpty())

            val (_, loggedErrors) = captureLoggedErrors {
                myFixture.type(typed)
                documents.commitAllDocuments()
                myFixture.doHighlighting()
            }

            loggedErrors.map { "typed ${typed.length} space(s): ${it.message}" } +
                injected().mapNotNull { file ->
                    val document = documents.getDocument(file)

                    if (document?.text == file.text) null
                    else "typed ${typed.length} space(s): the injected document is <${document?.text}> but its file <${file.text}>"
                }
        }

        assertEmpty(problems.joinToString("\n"), problems)
    }
}
