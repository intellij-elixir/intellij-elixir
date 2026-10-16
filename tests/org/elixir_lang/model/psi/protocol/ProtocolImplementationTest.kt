package org.elixir_lang.model.psi.protocol

import com.intellij.psi.ResolveState
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.declaration.ArityKnowledge
import org.elixir_lang.declaration.Declaration
import org.elixir_lang.declaration.Definer
import org.elixir_lang.psi.DefinitionDeclaration
import org.elixir_lang.psi.call.Call

/** The one rule: a protocol function is implemented by a public run-time function of its name and arity. */
class ProtocolImplementationTest : PlatformTestCase() {
    fun testOnlyPublicRuntimeDefinersImplementAFunctionMember() {
        val member = declarations("def size(data)").single()
        val implementing = Definer.entries.filter { definer ->
            ProtocolImplementation.implementedBy(member, declarations(written(definer, "size", "x")).single())
        }

        assertEquals(listOf(Definer.DEF, Definer.DEFMEMO), implementing)
    }

    fun testDelegationImplementsAFunctionMember() {
        val member = declarations("def size(data)").single()
        val delegation = declarations("defdelegate size(atom), to: Sizes").single()

        assertTrue(ProtocolImplementation.implementedBy(member, delegation))
    }

    fun testDelegationWithDefaultsImplementsEachArity() {
        val delegation = declarations("defdelegate size(atom, opts \\\\ []), to: Sizes").single()

        assertTrue(ProtocolImplementation.implementedBy(declarations("def size(data)").single(), delegation))
        assertTrue(ProtocolImplementation.implementedBy(declarations("def size(data, opts)").single(), delegation))
        assertFalse(ProtocolImplementation.implementedBy(declarations("def size(a, b, c)").single(), delegation))
    }

    fun testNothingImplementsAMacroMember() {
        val member = declarations("defmacro size(data), do: data").single()
        val implementers = Definer.entries.map { declarations(written(it, "size", "x")).single() } +
            declarations("defdelegate size(atom), to: Sizes").single()

        assertEquals(emptyList<Declaration>(), implementers.filter { ProtocolImplementation.implementedBy(member, it) })
    }

    fun testNameAndArityMustMeet() {
        val member = declarations("def size(data)").single()

        assertFalse("another name", implementedBy(member, "def count(data)"))
        assertFalse("a disjoint arity", implementedBy(member, "def size(data, opts)"))
        assertTrue("defaults cover the arity", implementedBy(member, "def size(data, opts \\\\ [])"))
    }

    /** Only an EEx `function_from_*` with a non-literal arguments list has an arity not yet knowable. */
    fun testAnUnknownArityImplementsNothing() {
        val member = declarations("def size(data)").single()
        val implementer = declarations("def size(x)").single().copy(arity = ArityKnowledge.Unknown)

        assertFalse(ProtocolImplementation.implementedBy(member, implementer))
    }

    fun testAProtocolFunctionSymbolIsImplementedAtItsArityOnly() {
        val file = myFixture.configureByText("size.ex", "defprotocol Size do\n  def size(data, opts \\\\ [])\nend\n")
        val clause = PsiTreeUtil.findChildrenOfType(file, Call::class.java).first { it.functionName() == "def" }
        val members = ProtocolFunction.fromClause(clause).associateBy { it.arity }
        val implementer = declarations("def size(data)").single()

        assertTrue(ProtocolImplementation.implementedBy(members.getValue(1), implementer))
        assertFalse(ProtocolImplementation.implementedBy(members.getValue(2), implementer))
    }

    private fun implementedBy(member: Declaration, implementer: String): Boolean =
        ProtocolImplementation.implementedBy(member, declarations(implementer).single())

    private fun written(definer: Definer, name: String, parameter: String): String =
        when (definer) {
            Definer.DEFGUARD, Definer.DEFGUARDP -> "${definer.keyword} $name($parameter) when is_atom($parameter)"
            else -> "${definer.keyword} $name($parameter), do: $parameter"
        }

    /** What each clause or delegation written in `defmodule M` defines. */
    private fun declarations(code: String): List<Declaration> {
        val file = myFixture.configureByText("definition.ex", "defmodule M do\n  $code\nend\n")

        return PsiTreeUtil.findChildrenOfType(file, Call::class.java)
            .filter { DefinitionDeclaration.`is`(it) }
            .mapNotNull { DefinitionDeclaration.of(it, ResolveState.initial()) }
    }
}
