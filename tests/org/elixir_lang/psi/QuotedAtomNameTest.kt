package org.elixir_lang.psi

import com.intellij.openapi.application.runReadAction
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.language_level.elixir
import org.elixir_lang.code_insight.gotoDeclarationDestinationAtCaret
import org.elixir_lang.module.PutAttribute
import org.elixir_lang.module.RegisterAttribute
import org.elixir_lang.psi.call.Call

/** A quoted atom is named by the atom it quotes to, as the readers of an atom's name that compare it need. */
class QuotedAtomNameTest : PlatformTestCase() {
    override fun tearDown() {
        try {
            ElixirLanguageLevelResolver.overrideLanguageLevel(project, null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testAtomNames() {
        myFixture.configureByText(
            "atoms.ex",
            "[:b, :\"b\", :\"a-b\", :\"\\x62\", :\"\\u0062\", :\"\", :+, :\"+\", :\"a#{x}\", :true, :\"true\"]\n"
        )

        assertEquals(
            listOf("b", "b", "a-b", "b", "b", "", "+", "+", null, "true", "true"),
            runReadAction { atoms().map { it.name } }
        )
    }

    fun testImplementationForAQuotedAtom() {
        assertEquals(
            listOf("P.lower", "P.lower"),
            implementationNames("defimpl P, for: :lower do\nend\n", "defimpl P, for: :\"lower\" do\nend\n")
        )
    }

    /** `Module.concat(P, :"Elixir.String")` is `P.String`: the `Elixir.` of an atom is the alias's own. */
    fun testImplementationForAnElixirPrefixedAtomIsTheAliasModule() {
        assertEquals(
            listOf("P.String", "P.String"),
            implementationNames("defimpl P, for: String do\nend\n", "defimpl P, for: :\"Elixir.String\" do\nend\n")
        )
    }

    /** `Module.concat/2` also drops a leading `.` of an atom. */
    fun testImplementationForADottedAtomIsTheAliasModule() {
        assertEquals(listOf("P.String"), implementationNames("defimpl P, for: :\".String\" do\nend\n"))
    }

    fun testImplementationForAnEscapedAtom() {
        assertEquals(listOf("P.lower"), implementationNames("defimpl P, for: :\"low\\x65r\" do\nend\n"))
    }

    fun testRegisteredAttributeWithAQuotedAtom() {
        myFixture.configureByText(
            "register.ex",
            "defmodule M do\n" +
                "  Module.register_attribute(__MODULE__, :acc, accumulate: true)\n" +
                "  Module.register_attribute(__MODULE__, :\"acc2\", accumulate: true)\n" +
                "  Module.register_attribute(__MODULE__, :\"ac\\x63\\x33\", accumulate: true)\n" +
                "end\n"
        )

        assertEquals(
            listOf("@acc", "@acc2", "@acc3"),
            runReadAction { calls("register_attribute").map { RegisterAttribute.name(it) } }
        )
    }

    fun testPutAttributeWithAQuotedAtom() {
        myFixture.configureByText(
            "put.ex",
            "defmodule M do\n" +
                "  Module.put_attribute(__MODULE__, :c, 1)\n" +
                "  Module.put_attribute(__MODULE__, :\"c2\", 1)\n" +
                "end\n"
        )

        assertEquals(listOf("@c", "@c2"), runReadAction { calls("put_attribute").map { PutAttribute.name(it) } })
    }

    /** The attribute a call registers is the one a later read names, whichever way the atom is spelled. */
    fun testRegisteredMicroSignStillResolvesItsRead() {
        for (level in listOf(elixir("1.14.0"), elixir("1.13.0"))) {
            ElixirLanguageLevelResolver.overrideLanguageLevel(project, level)
            myFixture.configureByText(
                "micro.ex",
                "defmodule M do\n  Module.register_attribute(__MODULE__, :µ, accumulate: true)\n  @µ 1\n  def f, do: @<caret>µ\nend\n"
            )

            val declaration = myFixture.gotoDeclarationDestinationAtCaret()

            assertNotNull("at $level", declaration)
            assertEquals("at $level", "@µ 1", declaration!!.parent.parent.text)
        }
    }

    /** `defimpl P, for: :µ` is the one implementation module Elixir names at the language level. */
    fun testImplementationForTheMicroSignIsOneModule() {
        for (level in listOf(elixir("1.14.0"), elixir("1.13.0"))) {
            ElixirLanguageLevelResolver.overrideLanguageLevel(project, level)

            // Elixir 1.14 folds the micro sign to the Greek mu in an atom, and earlier releases keep it.
            val expected = if (level == elixir("1.14.0")) "P.\u03bc" else "P.\u00b5"

            assertEquals("at $level", listOf(expected), implementationNames("defimpl P, for: :µ do\nend\n"))
        }
    }

    private fun atoms(): List<ElixirAtom> =
        PsiTreeUtil.findChildrenOfType(myFixture.file, ElixirAtom::class.java).toList()

    private fun calls(functionName: String): List<Call> =
        PsiTreeUtil.findChildrenOfType(myFixture.file, Call::class.java).filter { it.functionName() == functionName }

    private fun implementationNames(vararg sources: String): List<String?> =
        sources.map { source ->
            myFixture.configureByText("impl.ex", source)

            runReadAction {
                Implementation.name(PsiTreeUtil.findChildrenOfType(myFixture.file, Call::class.java).single { Implementation.`is`(it) })
            }
        }
}
