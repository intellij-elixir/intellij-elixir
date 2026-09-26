package org.elixir_lang.psi

import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.call.Call

/**
 * A `defmodule(name, do: block)` that is the head of a definition names the definition, not a module, whichever
 * `def*` it is written with. The real `defmodule` is the control.
 */
class ModuleDefinitionHeadTest : PlatformTestCase() {
    fun testADefinitionHeadNamedDefmoduleIsNotAModule() {
        myFixture.configureByText(
            "definition_heads.ex",
            """
            defmodule Redefinitions do
              defmacro defmodule(name, do: block), do: {name, block}
              def defmodule(name, do: block), do: {name, block}
            end
            """.trimIndent()
        )

        val actual = PsiTreeUtil.findChildrenOfType(myFixture.file, Call::class.java)
            .filter { it.functionName() == "defmodule" }
            .joinToString("\n") { "${it.text.lineSequence().first().take(40)} -> ${Module.`is`(it)}" }

        assertEquals(
            """
            defmodule Redefinitions do -> true
            defmodule(name, do: block) -> false
            defmodule(name, do: block) -> false
            """.trimIndent(),
            actual
        )
    }
}
