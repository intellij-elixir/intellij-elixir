package org.elixir_lang.psi

import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.SyntacticCall
import org.elixir_lang.psi.impl.MaybeModuleNameImpl

/** Which `defmodule`, `defprotocol` and `defimpl` calls define a module: those with a `do` that no caller quotes. */
class DefiningClauseTest : PlatformTestCase() {
    /** The head of a `defmacro defprotocol` is an argument `defmacro` quotes, not a `defprotocol` call. */
    fun testTheHeadOfADefmacroDefprotocolIsNoProtocol() {
        assertFalse(Protocol.`is`(defmacroHead("defprotocol")))
    }

    /** The head of a `defmacro defmodule` is an argument `defmacro` quotes, not a `defmodule` call. */
    fun testTheHeadOfADefmacroDefmoduleIsNoModule() {
        assertFalse(Module.isDefmodule(defmacroHead("defmodule")))
    }

    fun testADefprotocolWithADoIsAProtocol() {
        assertTrue(Protocol.`is`(call("defprotocol Foo do\nend\n", "defprotocol")))
    }

    /** A `defmodule` its caller quotes names no module, so its argument is no module name. */
    fun testTheArgumentOfAQuotedDefmoduleIsNoModuleName() {
        assertFalse(MaybeModuleNameImpl.isModuleName(oneArgument("defmacro(defmodule Foo do\n  1\nend)\n")))
    }

    fun testTheArgumentOfADefmoduleIsAModuleName() {
        assertTrue(MaybeModuleNameImpl.isModuleName(oneArgument("defmodule Foo do\nend\n")))
    }

    /** The head of a `defmacro defmodule` defines nothing. */
    fun testTheHeadOfADefmacroDefmoduleIsNoDefinition() {
        assertNull(definition(defmacroHead("defmodule")))
    }

    /** The head of a `defmacro defprotocol` defines nothing. */
    fun testTheHeadOfADefmacroDefprotocolIsNoDefinition() {
        assertNull(definition(defmacroHead("defprotocol")))
    }

    /** The head of a `defmacro defimpl` is an argument `defmacro` quotes, not a `defimpl` call. */
    fun testTheHeadOfADefmacroDefimplIsNoImplementation() {
        assertFalse(Implementation.`is`(defmacroHead("defimpl", "name, opts")))
    }

    /** The head of a `defmacro defimpl` defines nothing. */
    fun testTheHeadOfADefmacroDefimplIsNoDefinition() {
        assertNull(definition(defmacroHead("defimpl", "name, opts")))
    }

    fun testADefimplWithADoIsAnImplementation() {
        assertTrue(Implementation.`is`(call("defimpl Foo, for: Bar do\nend\n", "defimpl")))
    }

    fun testADefmoduleWithADoDefinesAModule() {
        assertEquals(Definition.MODULE, definition(call("defmodule Foo do\nend\n", "defmodule")))
    }

    /** The argument of the one call written without parentheses in [text]. */
    private fun oneArgument(text: String): ElixirNoParenthesesOneArgument =
        PsiTreeUtil.findChildOfType(myFixture.configureByText("a.ex", text), ElixirNoParenthesesOneArgument::class.java)!!

    /** The head of `defmacro [macro]([parameters], do: block)`, the `[macro]` call it quotes. */
    private fun defmacroHead(macro: String, parameters: String = "name"): SyntacticCall =
        call("defmacro $macro($parameters, do: block) do\n  name\nend\n", macro)

    /** The first call named [name] in a file holding [text]. */
    private fun call(text: String, name: String): SyntacticCall {
        val file = myFixture.configureByText("a.ex", text)

        return SyntacticCall.of(PsiTreeUtil.findChildrenOfType(file, Call::class.java).first { it.functionName() == name })
    }
}
