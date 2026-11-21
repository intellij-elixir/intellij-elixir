package org.elixir_lang.expander

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.VfsTestUtil
import com.intellij.util.system.OS
import org.elixir_lang.junit.HeavyTestCase
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.Meta
import org.elixir_lang.psi.ElixirFile
import java.util.concurrent.Callable

/**
 * A kept quote's file, read from files on disk: on Windows their paths have a drive, as a real project's do, where a
 * light fixture's `/src/...` has none and so isn't absolute.
 */
class ProjectModulesKeptQuoteHeavyTest : HeavyTestCase() {
    private val holder: ProjectModules get() = ProjectModules.getInstance(project)
    private val inputs = ProjectModules.Inputs(ElixirLanguageLevel.of("1.13.4"), legKernel, OS.CURRENT)
    private lateinit var root: VirtualFile

    override fun setUp() {
        super.setUp()
        root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(createTempDir("kept_quotes"))!!
        val module = createModule("kept_quotes")
        PsiTestUtil.addContentRoot(module, root)
        PsiTestUtil.addSourceRoot(module, root)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    /**
     * Before 1.14.4 a kept quote's file is relative to the directory of the innermost Mix project holding it, each
     * umbrella app's own, and unknown outside any.
     */
    fun testAKeptQuotesFileIsRelativeToItsOwnMixProject() {
        add("umbrella/mix.exs", MIX_EXS)
        add("umbrella/apps/a/mix.exs", MIX_EXS)
        add("umbrella/apps/b/mix.exs", MIX_EXS)
        val files = listOf(
            "a" to add("umbrella/apps/a/lib/a.ex", kept("A") + caller("A")),
            "b" to add("umbrella/apps/b/lib/nested/b.ex", kept("B") + caller("B")),
            "outside" to add("outside.ex", kept("Outside") + caller("Outside")),
        )

        assertEquals(
            "a: lib/a.ex\nb: lib/nested/b.ex\noutside: none",
            files.joinToString("\n") { (name, file) -> "$name: ${keptFiles(file)}" },
        )
    }

    /** A macro's clauses read for a sibling umbrella app keep its file relative to the macro's own app. */
    fun testAKeptQuoteReadFromASiblingAppIsRelativeToItsOwnApp() {
        add("umbrella/mix.exs", MIX_EXS)
        add("umbrella/apps/a/mix.exs", MIX_EXS)
        add("umbrella/apps/b/mix.exs", MIX_EXS)
        add("umbrella/apps/a/lib/a.ex", kept("A"))
        val b = add("umbrella/apps/b/lib/b.ex", caller("A"))

        assertEquals("lib/a.ex", keptFiles(b))
    }

    /** A `mix.exs` written above a file that keeps a quote's location moves the file's directory. */
    fun testAMixProjectAddedAboveAKeptQuoteRecomputes() {
        val a = add("later/lib/a.ex", kept("A"))
        val before = expansion(holder, a, inputs)

        add("later/mix.exs", MIX_EXS)
        val after = expansion(holder, a, inputs)

        assertEquals("a mix.exs added above", render(a, freshExpansion(project, a, inputs)), render(a, after))
        assertFalse("A's answer moves", render(a, before) == render(a, after))
    }

    private fun add(path: String, text: String): PsiFile =
        PsiManager.getInstance(project).findFile(VfsTestUtil.createFile(root, path, text))!!

    /** A module [name] with a macro whose `quote` keeps its location. */
    private fun kept(name: String) = "defmodule $name do\n  defmacro m do\n    quote location: :keep, do: f()\n  end\nend\n"

    /** A module calling [name]'s `m`. */
    private fun caller(name: String) = "defmodule ${name}Caller do\n  require $name\n  def f, do: $name.m()\nend\n"

    /** The files the `keep:` entries name on the nodes [file]'s expansion enters, or `none`. */
    private fun keptFiles(file: PsiFile): String {
        val files = mutableListOf<String>()
        val observer = ExpansionObserver { node, _, _ ->
            for (key in node.meta.keys) {
                val keep = (key as? Meta.Key.Entry)?.takeIf { it.name == "keep" }?.value as? Meta.Value.Tuple ?: continue

                files += (keep.elements.first() as Meta.Value.Binary).text
            }
        }
        ReadAction.nonBlocking(Callable { holder.observe(file as ElixirFile, inputs, observer) }).executeSynchronously()

        return files.distinct().joinToString().ifEmpty { "none" }
    }

    private companion object {
        const val MIX_EXS = "defmodule MixProject do\nend\n"
    }
}
