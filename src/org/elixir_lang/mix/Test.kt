package org.elixir_lang.mix

import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.PsiManager
import com.intellij.psi.ResolveState
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.CallableDeclaration
import org.elixir_lang.file.containsFileWithSuffix
import org.elixir_lang.psi.CallDefinitionClause.nameArityInterval
import org.elixir_lang.psi.ElixirAccessExpression
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.ElixirLine
import org.elixir_lang.psi.ElixirList
import org.elixir_lang.psi.Quotable
import org.elixir_lang.psi.QuotableKeywordList
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.call.macroChildCallList
import org.elixir_lang.psi.impl.keywordValue
import org.elixir_lang.psi.impl.stripAccessExpression
import org.elixir_lang.psi.operation.capture.NonNumeric

/**
 * Mix-level detection of test files and test sources.
 *
 * The `_test.exs` filename convention and test-file discovery are owned by Mix (the build tool), not by ExUnit
 * (the framework): `mix test` finds files via `:test_paths`/`:test_pattern` and selects them with
 * `:test_load_filters` (which defaults to `[&String.ends_with?(&1, "_test.exs")]`). ExUnit itself never looks at
 * filenames; it runs whatever modules call `use ExUnit.Case`.
 */
object Test {
    /**
     * The default test file suffix, matching Mix's default `:test_load_filters` of
     * `[&String.ends_with?(&1, "_test.exs")]`. Used as the fallback whenever a project's `mix.exs` does not
     * specify a statically analyzable filter.
     */
    const val DEFAULT_TEST_SUFFIX = "_test.exs"

    /**
     * Whether [file] is an Elixir test file according to the governing `mix.exs`'s `:test_load_filters`, falling
     * back to [DEFAULT_TEST_SUFFIX] when the filter is absent or not statically analyzable.
     */
    @RequiresReadLock
    fun isTestFile(file: PsiFile): Boolean {
        if (file !is ElixirFile) return false
        val name = file.virtualFile?.name ?: return false

        return testSuffixes(file).any { name.endsWith(it) }
    }

    /**
     * Whether [directory] contains a test file (see [isTestFile]) at any depth, according to the governing
     * `mix.exs`'s `:test_load_filters`.
     */
    @RequiresReadLock
    fun containsTestFile(directory: PsiDirectory): Boolean =
        testSuffixes(directory).any { containsFileWithSuffix(directory, it) }

    /**
     * Whether [file] is a test file (see [isTestFile]) in a test source root. Not a cheap per-element check - it
     * queries the project file index and reads the governing `mix.exs` - so callers in hot paths (e.g. line
     * markers) should apply cheaper structural checks first and resolve the [PsiFile] once.
     */
    @RequiresReadLock
    fun isUnderTestSources(file: PsiFile): Boolean {
        val vFile = file.virtualFile ?: return false

        return ProjectRootManager.getInstance(file.project).fileIndex.isInTestSourceContent(vFile) &&
                isTestFile(file)
    }

    /**
     * The test file suffixes that apply to [context], read best-effort from its governing `mix.exs`. Always
     * returns at least [DEFAULT_TEST_SUFFIX].
     */
    private fun testSuffixes(context: PsiFileSystemItem): List<String> =
        governingMixExs(context)?.let { testSuffixesFor(it) } ?: listOf(DEFAULT_TEST_SUFFIX)

    private val TEST_SUFFIXES: Key<CachedValue<List<String>>> = Key.create("ELIXIR_MIX_TEST_SUFFIXES")

    private fun testSuffixesFor(mixExs: PsiFile): List<String> =
        CachedValuesManager.getCachedValue(mixExs, TEST_SUFFIXES) {
            val suffixes = (mixExs as? ElixirFile)?.let(::loadFilterSuffixes) ?: listOf(DEFAULT_TEST_SUFFIX)

            CachedValueProvider.Result.create(suffixes, mixExs)
        }

    /**
     * The nearest ancestor `mix.exs` of [context], or `null` if none. In umbrella projects this resolves to the
     * child app's `mix.exs`, which is correct: Mix does not inherit `:test_load_filters` from the umbrella root.
     */
    private fun governingMixExs(context: PsiFileSystemItem): PsiFile? {
        val psiManager = PsiManager.getInstance(context.project)
        val virtualFile = context.virtualFile
        var directory = if (virtualFile?.isDirectory == true) virtualFile else virtualFile?.parent

        while (directory != null) {
            directory.findChild(Project.MIX_EXS)?.let { return psiManager.findFile(it) }
            directory = directory.parent
        }

        return null
    }

    /**
     * The suffixes extracted from [mixExs]'s `:test_load_filters`, or `null` to fall back to the default when the
     * key is absent or any entry is not the statically analyzable `&String.ends_with?(&1, "<suffix>")` shape.
     */
    private fun loadFilterSuffixes(mixExs: ElixirFile): List<String>? {
        val keywords = projectKeywords(mixExs) ?: return null
        val value = keywords.keywordValue("test_load_filters") ?: return null

        return interpretLoadFilters(value)
    }

    /** The `:project` keyword list of the `def project do ... end` definition in [mixExs], if present. */
    private fun projectKeywords(mixExs: ElixirFile): QuotableKeywordList? =
        mixExs
            .modulars()
            .asSequence()
            .flatMap { it.macroChildCallList().asSequence() }
            .filter { call ->
                CallableDeclaration.definerOf(call)?.capabilities?.remoteCallable == true && nameArityInterval(call, ResolveState.initial())?.let { (name, arityInterval) ->
                    name == "project" && arityInterval.contains(0)
                } == true
            }
            .flatMap { it.doBlock?.stab?.stabBody?.children?.asSequence() ?: emptySequence() }
            .filterIsInstance<ElixirAccessExpression>()
            .mapNotNull(ElixirAccessExpression::getList)
            .mapNotNull(ElixirList::getKeywords)
            .firstOrNull()

    /**
     * Interprets a `:test_load_filters` [value]. Returns the list of suffixes only when every entry is the
     * reliably analyzable `&String.ends_with?(&1, "<suffix>")` capture; any regex, bare function capture, string,
     * or otherwise unrecognized entry collapses the whole list to `null` so the caller falls back to the default.
     *
     * This is something that we might be able to improve in the future.
     */
    private fun interpretLoadFilters(value: Quotable): List<String>? {
        val list = value.stripAccessExpression() as? ElixirList ?: return null
        val suffixes = list.children.map { interpretLoadFilter(it.stripAccessExpression()) }

        return if (suffixes.isNotEmpty() && suffixes.all { it != null }) suffixes.filterNotNull() else null
    }

    /** The suffix of a single `&String.ends_with?(&1, "<suffix>")` filter [entry], or `null` if not that shape. */
    private fun interpretLoadFilter(entry: PsiElement): String? {
        val capture = entry as? NonNumeric ?: return null
        val call = capture.operand()?.stripAccessExpression() as? Call ?: return null

        if (!call.isCalling("String", "ends_with?")) return null

        val arguments = call.finalArguments() ?: return null
        if (arguments.size != 2) return null

        return (arguments[1].stripAccessExpression() as? ElixirLine)?.body?.text
    }
}
