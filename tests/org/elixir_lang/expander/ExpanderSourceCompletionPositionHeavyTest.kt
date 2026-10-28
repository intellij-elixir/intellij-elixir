package org.elixir_lang.expander

import com.intellij.codeInsight.completion.CompletionUtil
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import org.elixir_lang.declaration.ArityKnowledge
import org.elixir_lang.declaration.CompletionPosition
import org.elixir_lang.declaration.Use
import org.elixir_lang.junit.HeavyTestCase
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.QualifiableAlias
import org.elixir_lang.psi.operation.capture.NonNumeric
import org.elixir_lang.reference.CaptureNameArity
import java.io.File
import java.util.concurrent.Callable

/**
 * What [ExpanderSource] makes visible at the positions completion runs at that are not a call, in completion's copy of
 * the file being edited, in a project whose module has the leg's SDK.
 */
class ExpanderSourceCompletionPositionHeavyTest : HeavyTestCase() {
    override fun setUp() {
        super.setUp()
        LegSdk.setUp(module, testRootDisposable)
    }

    override fun tearDown() {
        try {
            LegSdk.tearDown(module)
        } finally {
            super.tearDown()
        }
    }

    fun testAnAliasAfterADotSeesTheModulesPublicDefinitions() {
        val position = atCompletion(
            """
            defmodule Target do
              def pub(x), do: x
              def pub(x, y), do: {x, y}
              defp priv, do: :p
              defmacro mac(x), do: x
              defdelegate dele(x), to: Map
            end

            defmodule Caller do
              def go, do: Target.<caret>
            end
            """,
            typed = "",
        ) { leaf -> CompletionPosition.AfterDot(leaf.parent.parent as QualifiableAlias) }

        assertEquals(
            """
            dele/1 line 6
            mac/1 line 5
            pub/1 line 2
            """.trimIndent(),
            render(position),
        )
    }

    /** `pick`'s first clause has another arity than the capture's, and `padded` has no clause of its arity. */
    fun testAQualifiedCaptureSeesTheNamesWithAClauseOfItsArity() {
        val position = atCompletion(
            """
            defmodule Arities do
              def pick(a, b), do: {a, b}
              def pick(a), do: a
              def pad(a), do: a
              def padded(a, b), do: {a, b}
            end

            defmodule Caller do
              def go, do: &Arities.p<caret>/1
            end
            """,
            typed = "p",
        ) { leaf ->
            val reference = PsiTreeUtil.getParentOfType(leaf, NonNumeric::class.java)!!.reference as CaptureNameArity

            CompletionPosition.Capture(reference.nameElement, reference.arity)
        }

        assertEquals(
            """
            pad/1 line 4
            pick/1 line 3
            """.trimIndent(),
            render(position),
        )
    }

    fun testAnMfaAtomSeesTheModulesPublicDefinitions() {
        val position = atCompletion(
            """
            defmodule Mfa do
              def pub_one, do: :ok
              def pub_two, do: :ok
              defp priv, do: :ok
            end

            defmodule Caller do
              def go, do: apply(Mfa, :pub<caret>, [])
            end
            """,
            typed = "pub",
        ) { leaf ->
            val atom = PsiTreeUtil.getParentOfType(leaf, ElixirAtom::class.java)!!

            CompletionPosition.Mfa(atom, Use.mfa(atom)!!.module)
        }

        assertEquals(
            """
            pub_one/0 line 2
            pub_two/0 line 3
            """.trimIndent(),
            render(position),
        )
    }

    /**
     * The position [position] finds at the start of what is [typed] before `<caret>` in the completion copy of a file whose
     * text is [text]: the copy has the dummy identifier at `<caret>`, and the file has nothing.
     */
    private fun atCompletion(
        text: String,
        typed: String,
        position: (PsiElement) -> CompletionPosition,
    ): CompletionPosition {
        val source = text.trimIndent() + "\n"
        val file = file(source.replace("<caret>", ""))
        val copy = read {
            PsiFileFactory.getInstance(project)
                .createFileFromText(
                    file.name,
                    file.language,
                    source.replace("<caret>", CompletionUtil.DUMMY_IDENTIFIER),
                    false,
                    false,
                )
                .also { (it as PsiFileImpl).originalFile = file }
        }
        val offset = source.indexOf("<caret>") - typed.length

        return read { position(copy.findElementAt(offset)!!) }
    }

    /** `lib/source.ex` holding [text], in a source root of the test's module. */
    private fun file(text: String): PsiFile {
        val directory = createTempDir("source")
        FileUtil.writeToFile(File(directory, "lib/source.ex"), text)
        val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(directory)!!
        VfsUtil.markDirtyAndRefresh(false, true, true, root)
        PsiTestUtil.addContentRoot(module, root)
        PsiTestUtil.addSourceRoot(module, root.findChild("lib")!!)
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        return PsiManager.getInstance(project).findFile(root.findFileByRelativePath("lib/source.ex")!!)!!
    }

    /** What [ExpanderSource] makes visible at [position], in name order, each with its arity and the line that declares it. */
    private fun render(position: CompletionPosition): String =
        read {
            ExpanderSource.visible(position)
                .map { visible ->
                    val declaration = visible.declaration!!
                    val line = StringUtil.offsetToLineNumber(visible.element.containingFile.text, visible.element.textRange.startOffset) + 1

                    "${visible.lookupName}/${arity(declaration.arity)} line $line"
                }
                .sorted()
                .joinToString("\n")
        }

    private fun arity(arity: ArityKnowledge): String =
        when (arity) {
            is ArityKnowledge.Exact -> "${arity.arity}"
            is ArityKnowledge.Range -> "${arity.minimum}..${arity.maximum}"
            is ArityKnowledge.Open -> "${arity.minimum}+"
            ArityKnowledge.Unknown -> "?"
        }

    private fun <T> read(block: () -> T): T = ReadAction.nonBlocking(Callable { block() }).executeSynchronously()
}
