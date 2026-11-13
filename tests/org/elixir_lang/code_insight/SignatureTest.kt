package org.elixir_lang.code_insight

import org.elixir_lang.PlatformTestCase
import org.elixir_lang.beam.psi.BeamFileImpl
import org.elixir_lang.beam.psi.impl.CallDefinitionImpl
import org.elixir_lang.beam.psi.impl.CallDefinitionStubImpl
import org.elixir_lang.beam.psi.stubs.ModuleStub
import org.elixir_lang.psi.ArityInterval
import java.io.File

class SignatureTest : PlatformTestCase() {
    fun testBeamDefinitionKeepsItsStoredParameters() {
        assertEquals(listOf("x", "q"), beamSignature(2, listOf("x", "q"), false).parameters)
    }

    /** A stub stores no parameters when rendering the definition failed or no decompiler accepts its name. */
    fun testBeamDefinitionWithoutStoredParametersGetsGeneratedNames() {
        assertEquals(listOf("arg1", "arg2", "arg3"), beamSignature(3, emptyList(), true).parameters)
    }

    fun testBeamZeroArityHasNoParameters() {
        assertEquals(emptyList<String>(), beamSignature(0, emptyList(), false).parameters)
    }

    /** The head covers every arity its defaults leave, so a lower arity is described by the same signature. */
    fun testBeamDefinitionWithAHeadIsDescribedByTheHeadAtEveryArityItCovers() {
        val head = listOf("a", "b \\\\ 1", "c", "d \\\\ 2")

        assertEquals(
            Signature.of(beamDefinition(4, head, false, head)),
            Signature.of(beamDefinition(2, listOf("a", "c"), false, head))
        )
        assertEquals(ArityInterval(2, 4), Signature.of(beamDefinition(3, head, false, head)).nameArityInterval.arityInterval)
    }

    fun testBeamDefinitionWithAHeadPassesTheParametersLeftAtItsArity() {
        val head = listOf("a", "b \\\\ 1", "c", "d \\\\ 2")

        assertEquals(listOf("a", "c"), Signature.parametersAt(beamDefinition(2, listOf("a", "c"), false, head)))
        assertEquals(listOf("a", "b", "c"), Signature.parametersAt(beamDefinition(3, listOf("a", "b", "c"), false, head)))
        assertEquals(listOf("a", "b", "c", "d"), Signature.parametersAt(beamDefinition(4, head, false, head)))
    }

    /** A source function is hinted with the names Elixir records for it, not one clause's patterns. */
    fun testASourceFunctionIsHintedByTheSignatureElixirRecords() =
        assertEquals(
            listOf("socket"),
            sourceHint(
                """
                defmodule Hint do
                  defmodule Socket do
                    defstruct [:transport_pid]
                  end

                  def connected?(%Socket{transport_pid: pid}), do: pid != nil

                  def run(socket), do: connected?(<caret>socket)
                end
                """
            )
        )

    /** With no bare head, every clause names the parameters, as Elixir merges them, not the first clause alone. */
    fun testASourceFunctionWithoutABareHeadIsHintedByEveryClause() =
        assertEquals(
            listOf("list, nth, fun"),
            sourceHint(
                """
                defmodule Hint do
                  def map_every([], nth, _fun) when is_integer(nth) and nth > 1, do: []
                  def map_every(list, nth, fun), do: {list, nth, fun}

                  def run(list), do: map_every(<caret>list, 2, &(&1))
                end
                """
            )
        )

    /** A default read from a module attribute is hinted with the attribute's value, as Elixir records it. */
    fun testASourceDefaultFromAnAttributeIsHintedByItsValue() =
        assertEquals(
            listOf("""a, b \\ 5"""),
            sourceHint(
                """
                defmodule Hint do
                  @x 5

                  def f(a, b \\ @x), do: {a, b}

                  def run(a), do: f(<caret>a)
                end
                """
            )
        )

    /** Elixir records no signature for a private function; the hint names it by the same rule. */
    fun testASourcePrivateFunctionIsHintedByTheSameRule() =
        assertEquals(
            listOf("socket"),
            sourceHint(
                """
                defmodule Hint do
                  defmodule Socket do
                    defstruct [:transport_pid]
                  end

                  defp transport(%Socket{transport_pid: pid}), do: pid

                  def run(socket), do: transport(<caret>socket)
                end
                """
            )
        )

    /**
     * A clause a `use` injects is defined where the `use` is, so it names the parameters before a later local clause,
     * however far into its own file it is written.
     */
    fun testAnInjectedClauseBeforeALocalOneNamesTheParameters() {
        myFixture.addFileToProject("u.ex", using("U", "def f(a), do: a", padding = 400))

        assertEquals(
            listOf("a"),
            sourceHint(
                """
                defmodule Hint do
                  use U
                  def f(b), do: b

                  def run(x), do: f(<caret>x)
                end
                """
            )
        )
    }

    fun testALocalClauseBeforeAnInjectedOneNamesTheParameters() {
        myFixture.addFileToProject("u.ex", using("U", "def f(a), do: a"))

        assertEquals(
            listOf("b"),
            sourceHint(
                """
                defmodule Hint do
                  @moduledoc "${"padding ".repeat(50)}"

                  def f(b), do: b
                  use U

                  def run(x), do: f(<caret>x)
                end
                """
            )
        )
    }

    /** A clause a `use` inside a `use` injects is defined where the outer `use` is. */
    fun testAClauseInjectedByANestedUseIsDefinedAtTheOuterUse() {
        myFixture.addFileToProject("u.ex", using("U", "use V"))
        myFixture.addFileToProject("v.ex", using("V", "def n(a), do: a", padding = 400))

        assertEquals(
            listOf("a"),
            sourceHint(
                """
                defmodule Hint do
                  use U
                  def n(b), do: b

                  def run(x), do: n(<caret>x)
                end
                """
            )
        )
    }

    /** A module [name] whose `__using__/1` injects [quoted], after a `@moduledoc` of [padding] characters. */
    private fun using(name: String, quoted: String, padding: Int = 0): String =
        """
        defmodule $name do
          @moduledoc "${"p".repeat(padding)}"

          defmacro __using__(_) do
            quote do
              $quoted
            end
          end
        end
        """.trimIndent()

    private fun sourceHint(text: String): List<String> {
        myFixture.configureByText("hint.ex", text.trimIndent())

        return myFixture.parameterInfoSignaturesAtCaret()
    }

    private fun beamSignature(arity: Int, parameters: List<String>, isAutoGeneratedName: Boolean): Signature =
        Signature.of(beamDefinition(arity, parameters, isAutoGeneratedName))

    private fun beamDefinition(
        arity: Int,
        parameters: List<String>,
        isAutoGeneratedName: Boolean,
        head: List<String> = emptyList()
    ): CallDefinitionImpl<*> {
        val bytes = File("testData/org/elixir_lang/code_insight/parameter_info/beam/ebin/queue.beam").readBytes()
        val module = BeamFileImpl.buildFileStub(bytes, "queue.beam")!!.childrenStubs.single() as ModuleStub<*>
        val stub = CallDefinitionStubImpl<CallDefinitionImpl<*>>(
            module, "def", "unrendered", arity, parameters, isAutoGeneratedName, head, isGuard = false
        )

        return CallDefinitionImpl(stub)
    }
}
