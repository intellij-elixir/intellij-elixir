package org.elixir_lang.inspection

import org.elixir_lang.PlatformTestCase

/** A module of `mix.exs` that uses `Mix.Project` after another one does. */
class SecondMixProjectTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(SecondMixProject::class.java)
    }

    fun testTheSecondMixProjectIsMarkedAndTheFirstIsNot() {
        assertEquals(listOf("Second"), marked("mix.exs", module("First", "use Mix.Project") + module("Second", "use Mix.Project")))
    }

    fun testASingleMixProjectIsNotMarked() {
        assertEquals(emptyList<String>(), marked("mix.exs", module("First", "use Mix.Project") + module("Helper", "")))
    }

    /** The nested module is defined before the outer one's `use`, which may alias any name in what follows it. */
    fun testANestedSecondMixProjectIsMarked() {
        assertEquals(
            listOf("Inner"),
            marked("mix.exs", module("Outer", module("Inner", "use Mix.Project") + "use Mix.Project")),
        )
    }

    /** A `use` before it may alias `Mix`, so whether the module uses `Mix.Project` isn't known. */
    fun testASecondModuleWhoseUseIsUnknownIsNotMarked() {
        assertEquals(emptyList<String>(), marked("mix.exs", module("First", "use Mix.Project") + module("Second", "use Foo\nuse Mix.Project")))
    }

    fun testAFileNotNamedMixExsIsNotMarked() {
        assertEquals(emptyList<String>(), marked("other.exs", module("First", "use Mix.Project") + module("Second", "use Mix.Project")))
    }

    fun testTheMessageSaysMixWillFailToCompile() {
        myFixture.configureByText("mix.exs", module("First", "use Mix.Project") + module("Second", "use Mix.Project"))

        assertEquals(
            listOf("Mix will fail to compile this project: another module in this file already uses `Mix.Project`"),
            highlights().map { it.description },
        )
    }

    /** The text of each module name marked in a file named [fileName] holding [text]. */
    private fun marked(fileName: String, text: String): List<String> {
        myFixture.configureByText(fileName, text)

        return highlights().map { it.text }
    }

    private fun highlights() = myFixture.doHighlighting().filter { it.inspectionToolId == SHORT_NAME }

    /** A module [name] whose body is [body]. */
    private fun module(name: String, body: String): String = "defmodule $name do\n${body.prependIndent("  ")}\nend\n\n"

    private companion object {
        const val SHORT_NAME = "SecondMixProject"
    }
}
