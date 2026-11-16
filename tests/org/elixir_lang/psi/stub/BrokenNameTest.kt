package org.elixir_lang.psi.stub

import com.intellij.psi.PsiNamedElement
import com.intellij.psi.ResolveState
import com.intellij.psi.stubs.IStubElementType
import com.intellij.psi.stubs.IndexSink
import com.intellij.psi.stubs.StubElement
import com.intellij.psi.stubs.StubIndexKey
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.getModuleNames
import org.elixir_lang.psi.stub.type.File

/** Naming broken code answers without throwing, or stub building and indexing would fail for the whole file. */
class BrokenNameTest : PlatformTestCase() {
    fun testNoNameThrows() {
        val thrown = SNIPPETS.mapNotNull { snippet ->
            try {
                name(snippet)
                null
            } catch (exception: Throwable) {
                "${snippet.replace("\n", "\\n")}: $exception at ${exception.stackTrace.firstOrNull()}"
            }
        }

        assertEquals("", thrown.joinToString("\n"))
    }

    private fun name(source: String) {
        val file = myFixture.configureByText("broken.ex", source)
        val sink = object : IndexSink {
            override fun <Psi : com.intellij.psi.PsiElement, K : Any> occurrence(indexKey: StubIndexKey<K, Psi>, value: K) {
            }
        }
        index(File.INSTANCE.builder.buildStubTree(file), sink)

        for (call in PsiTreeUtil.findChildrenOfType(file, Call::class.java)) {
            (call as? PsiNamedElement)?.name
            if (CallDefinitionClause.`is`(call)) CallDefinitionClause.nameArityInterval(call, ResolveState.initial())
            call.getModuleNames()
        }
    }

    private fun index(stub: StubElement<*>, sink: IndexSink) {
        @Suppress("UNCHECKED_CAST")
        (stub.stubType as? IStubElementType<StubElement<*>, *>)?.indexStub(stub, sink)
        stub.childrenStubs.forEach { index(it, sink) }
    }

    private companion object {
        val SNIPPETS = listOf(
            "defmodule :\"#{)\" do\nend\n",
            "defmodule :\"#{\" do\nend\n",
            "defmodule :\"#{1 2}\" do\nend\n",
            "defmodule Foo. do\nend\n",
            "defmodule Foo.( do\nend\n",
            "defmodule x in %Foo do\nend\n",
            "defmodule -%Foo do\nend\n",
            "defmodule \"\"\"\nabc",
            "defmodule M do\n  def unquote(:\"#{)\")(), do: 1\nend\n",
            "defmodule M do\n  def unquote(:\"#{\")(), do: 1\nend\n",
            "defmodule M do\n  def unquote(\n",
            "defmodule M do\n  def unquote(x in %Foo)(), do: 1\nend\n",
            "defmodule M do\n  @spec unquote(:\"#{)\")(integer) :: integer\nend\n",
            "defmodule M do\n  defdelegate unquote(:\"#{)\")(a), to: N\nend\n",
            "defimpl :\"#{)\", for: X do\nend\n",
            "defimpl P, for: :\"#{)\" do\nend\n",
            "defimpl P, for: [Elixir., X] do\nend\n",
            "defimpl Foo., for: X do\nend\n",
        )
    }
}
