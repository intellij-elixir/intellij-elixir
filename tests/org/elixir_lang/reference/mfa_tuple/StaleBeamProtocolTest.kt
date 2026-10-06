package org.elixir_lang.reference.mfa_tuple

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import org.elixir_lang.beam.BeamLibraryTestCase
import org.elixir_lang.code_insight.line_marker_provider.Implementation
import java.io.File

/**
 * A protocol `Proto` in `lib/proto.ex` has a compiled copy of it beside it (`Elixir.Proto.beam`, left over from an older
 * build). Where a protocol is looked up by its name the compiled copy adds nothing: the gutter icon on `defimpl` has the
 * source protocol as its one target, and `Proto` is offered once in atom and alias completion, as the source.
 */
class StaleBeamProtocolTest : BeamLibraryTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/reference/mfa_tuple/stale_beam_proto"

    override val ebinDirectory: File
        get() = File(testDataPath, "ebin").absoluteFile

    override fun setUp() {
        super.setUp()

        myFixture.addFileToProject("lib/proto.ex", "defprotocol Proto do\n  def p(t)\nend\n")
        myFixture.addFileToProject("lib/proto_other.ex", "defmodule ProtoOther do\nend\n")
    }

    fun testImplementationGutterHasTheSourceProtocolOnly() {
        myFixture.configureByText("impl.ex", "defimpl Proto, for: Integer do\n  def p(t), do: t\nend\n")

        val targets = protocolGutterTargets("defimpl")

        assertEquals(listOf("proto.ex"), targets.map { it.fileName() })
    }

    fun testAtomCompletionOffersTheModuleOnce() {
        myFixture.configureByText("caller.ex", "defmodule Caller do\n  def run, do: :\"Elixir.Pro<caret>\"\nend\n")

        val offered = candidates()

        assertEquals(offered.toString(), 1, offered.count { it.lookupString == "Proto" })
    }

    fun testAliasCompletionOffersTheModuleOnceAsTheSource() {
        myFixture.configureByText("caller.ex", "defmodule Caller do\n  def run, do: Pro<caret>\nend\n")

        val proto = candidates().filter { it.lookupString == "Proto" }

        assertEquals(proto.toString(), listOf("proto.ex"), proto.map { it.psiElement.fileName() })
    }

    private fun candidates(): List<LookupElement> =
        myFixture.completeBasic()?.toList() ?: throw AssertionError("completion opened no popup")

    private fun protocolGutterTargets(anchorText: String): List<PsiElement> {
        val targets = mutableListOf<PsiElement>()

        myFixture.file.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                super.visitElement(element)

                if (element.firstChild == null && element.text == anchorText) {
                    (Implementation().getLineMarkerInfo(element) as? RelatedItemLineMarkerInfo<*>)
                        ?.let { info -> targets += info.createGotoRelatedItems().mapNotNull { it.element } }
                }
            }
        })

        return targets
    }
}
