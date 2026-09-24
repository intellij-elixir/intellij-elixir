package org.elixir_lang.psi.impl

import com.ericsson.otp.erlang.OtpErlangObject
import com.intellij.lang.ASTNode
import com.intellij.psi.impl.source.tree.FileElement
import com.intellij.psi.stubs.DefaultStubBuilder
import com.intellij.util.concurrency.annotations.RequiresEdt
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.Quotable

class StubBuildQuotingTest : PlatformTestCase() {
    /**
     * No language level override, so the level goes through its cached value; the `~S` sigil's escaped newline is one
     * older Elixir did not count, so each quoted line also looks up the uncounted newlines.
     */
    @RequiresEdt
    fun testStubBuildQuotesTheSameLinesWithoutPlainCachedValues() {
        val file = myFixture.configureByText(
            "sigil.ex",
            "defmodule Sigil do\n  @a ~S(a\\\nb)\n  def b, do: [c: 1]\nend\n"
        )
        val quotedInBuild = mutableListOf<OtpErlangObject>()
        val builder = object : DefaultStubBuilder() {
            override fun skipChildProcessingWhenBuildingStubs(parent: ASTNode, node: ASTNode): Boolean {
                if (parent is FileElement) (node.psi as? Quotable)?.let { quotedInBuild.add(it.quote()) }

                return false
            }
        }

        val (_, warning) = captureLoggedWarning("com.intellij.psi.util.CachedValuesManager") {
            builder.buildStubTree(file)
        }

        assertEquals(file.children.filterIsInstance<Quotable>().map { it.quote() }, quotedInBuild)
        // The platform throttles this warning to one per 5 s, so another test's warning just before can hide it.
        assertNull("a stub build reached a plain cached value", warning)
    }
}
