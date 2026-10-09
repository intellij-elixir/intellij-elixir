package org.elixir_lang.documentation

import org.elixir_lang.ElixirFileType
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.language_level.elixir

/**
 * A `t:Module.name/arity` link opens the type by the name Elixir reads it as: from 1.14 `µ` (the micro sign) is the type
 * `μ` (the Greek mu), and `café` written decomposed is `café` precomposed. Before 1.14 each spelling is a type of its own.
 */
class TypeLinkNormalFormsTest : PlatformTestCase() {
    override fun tearDown() {
        try {
            ElixirLanguageLevelResolver.overrideLanguageLevel(project, null)
        } finally {
            super.tearDown()
        }
    }

    fun testMicroSignLinkOpensTheMicroSignType() = assertOpens("µ", "t:M.µ/0")
    fun testGreekMuLinkOpensTheMicroSignType() = assertOpens("µ", "t:M.μ/0")
    fun testMicroSignLinkOpensTheGreekMuType() = assertOpens("μ", "t:M.µ/0")
    fun testDecomposedLinkOpensThePrecomposedType() = assertOpens(PRECOMPOSED, "t:M.$DECOMPOSED/0")
    fun testPrecomposedLinkOpensTheDecomposedType() = assertOpens(DECOMPOSED, "t:M.$PRECOMPOSED/0")

    fun testMicroSignLinkOpensTheMicroSignTypeBefore1_14() = assertOpens("µ", "t:M.µ/0", elixir("1.13.0"))
    fun testGreekMuLinkDoesNotOpenTheMicroSignTypeBefore1_14() = assertNotOpens("µ", "t:M.μ/0", elixir("1.13.0"))

    private fun assertOpens(declared: String, link: String, level: ElixirLanguageLevel = elixir("1.14.0")) {
        val element = open(declared, link, level)

        assertNotNull("$link should open the type $declared", element)
        assertTrue(element!!.text, element.text.contains(declared))
    }

    private fun assertNotOpens(declared: String, link: String, level: ElixirLanguageLevel) =
        assertNull("$link opens a type named $declared", open(declared, link, level))

    private fun open(declared: String, link: String, level: ElixirLanguageLevel) =
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, level).let {
            myFixture.configureByText(ElixirFileType.INSTANCE, "defmodule M do\n  @type $declared :: term()\nend\n")

            ElixirDocumentationProvider().getDocumentationElementForLink(psiManager, link, myFixture.file)
        }

    private companion object {
        const val DECOMPOSED = "café"
        const val PRECOMPOSED = "café"
    }
}
