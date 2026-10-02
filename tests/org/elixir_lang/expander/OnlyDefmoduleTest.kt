package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageLevel

/** [onlyDefmodule]: whether a file's forms are all `defmodule Name, do: block`, which Elixir compiles directly. */
class OnlyDefmoduleTest : ExpanderTestCase() {
    fun testOneModule() = assertOnly("defmodule A do\n  def f, do: 1\nend", true)

    fun testTwoModules() = assertOnly("defmodule A do\nend\n\ndefmodule B do\nend", true)

    fun testAModuleWithADoKeyword() = assertOnly("defmodule A, do: :ok", true)

    fun testModulesInAParenthesisedBlock() = assertOnly("(defmodule A do end; defmodule B do end)", true)

    fun testAModuleAndAnotherForm() = assertOnly("defmodule A do\nend\n\n:ok", false)

    fun testAModuleWithAnElseBlock() = assertOnly("defmodule A do\n  :ok\nelse\n  :error\nend", false)

    fun testARemoteDefmodule() = assertOnly("Kernel.defmodule A do\nend", false)

    fun testAnotherFormAlone() = assertOnly(":ok", false)

    private fun assertOnly(code: String, expected: Boolean) =
        assertEquals(expected, onlyDefmodule(lower(code, ElixirLanguageLevel.of("1.20.4"))))
}
