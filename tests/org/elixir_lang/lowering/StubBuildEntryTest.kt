package org.elixir_lang.lowering

import com.ericsson.otp.erlang.OtpErlangObject
import com.intellij.lang.ASTNode
import com.intellij.psi.impl.source.tree.FileElement
import com.intellij.psi.stubs.DefaultStubBuilder
import com.intellij.util.concurrency.annotations.RequiresEdt
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.psi.Quotable

/** [ElementLowering] inside a stub build, where the platform flags a plain cached value. */
class StubBuildEntryTest : PlatformTestCase() {
    @RequiresEdt
    fun testStubBuildLowersTheSameWithoutPlainCachedValues() = assertStubBuildLowers()

    /** An older level scans the tree for the newlines its tokenizer leaves uncounted, here the `~S` sigil's. */
    @RequiresEdt
    fun testStubBuildAtAnOlderLevelLowersTheSameWithoutPlainCachedValues() {
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, ElixirLanguageLevel.of("1.11.4"))

        try {
            assertStubBuildLowers()
        } finally {
            ElixirLanguageLevelResolver.overrideLanguageLevel(project, null)
        }
    }

    private fun assertStubBuildLowers() {
        val file = myFixture.configureByText(
            "sigil.ex",
            "defmodule Sigil do\n  @a ~S(a\\\nb)\n  def b, do: [c: 1]\n  defmodule :d do\n    import E, only: [f: 1]\n  end\nend\n"
        )
        val quotables = file.children.filterIsInstance<Quotable>() + quotedElements(file).map { it.second }
        val quotedInBuild = mutableListOf<OtpErlangObject>()
        val builder = object : DefaultStubBuilder() {
            override fun skipChildProcessingWhenBuildingStubs(parent: ASTNode, node: ASTNode): Boolean {
                if (parent is FileElement && quotedInBuild.isEmpty()) quotables.mapTo(quotedInBuild, ElementLowering::quote)

                return false
            }
        }

        builder.buildStubTree(file)

        assertNull("a stub build cached line starts in a plain cached value", file.getUserData(ElementLowering.LINE_STARTS))
        assertEquals(quotables.map(ElementLowering::quote), quotedInBuild)
    }
}
