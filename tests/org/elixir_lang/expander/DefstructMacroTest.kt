package org.elixir_lang.expander

import com.intellij.openapi.util.TextRange
import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Meta
import org.elixir_lang.lowering.inspect
import org.elixir_lang.psi.Import.Term
import java.math.BigInteger

/**
 * `defstruct`: the two functions it defines, the events of the output Elixir expands it to on each release, and each
 * way it raises. The output is `Kernel.defstruct/1`'s and `Kernel.Utils.defstruct`'s, whose results the expander
 * computes from literal fields.
 */
class DefstructMacroTest : ExpanderTestCase() {
    override val exports: Exports = DefstructFixtures.EXPORTS
    override val kernel: KernelImports = DefstructFixtures.KERNEL

    fun testAStructDefinesItsTwoFunctions() =
        assertEvery("defmodule A do\n  defstruct [:a]\nend", "module Elixir.A compiled$TABLE")

    fun testDefaultsOfEveryLiteralKind() =
        assertEvery(
            "defmodule A do\n  defstruct a: 1, b: [1, 2], c: %{x: :y}, d: {1, :z}, e: \"s\", f: 1.5, g: nil\nend",
            "module Elixir.A compiled$TABLE",
        )

    fun testEnforcedKeysAsAListAreAccepted() =
        assertEvery(
            "defmodule A do\n  @enforce_keys [:a]\n  defstruct [:a, :b]\nend",
            "module Elixir.A compiled$TABLE_AT_3",
        )

    fun testAnEnforcedKeyOnItsOwnIsAccepted() =
        assertEvery(
            "defmodule A do\n  @enforce_keys :a\n  defstruct [:a]\nend",
            "module Elixir.A compiled$TABLE_AT_3",
        )

    fun testNoEnforcedKeysAreAcceptedAsAnEmptyList() =
        assertEvery(
            "defmodule A do\n  @enforce_keys []\n  defstruct [:a]\nend",
            "module Elixir.A compiled$TABLE_AT_3",
        )

    fun testFieldsThatAreNotLiteralStopTheModule() =
        assertEvery(
            "defmodule A do\n  defstruct Foo.fields()\nend",
            "module Elixir.A stopped `defstruct Foo.fields()`$TABLE",
        )

    fun testADefaultThatIsNotLiteralStopsTheModule() =
        assertEvery(
            "defmodule A do\n  defstruct a: Foo.default()\nend",
            "module Elixir.A stopped `defstruct a: Foo.default()`$TABLE",
        )

    fun testAnImproperListIsALiteralDefault() =
        assertEvery("defmodule A do\n  defstruct a: [1 | 2]\nend", "module Elixir.A compiled$TABLE")

    fun testAnImproperListDefaultIsEscapedAsABar() =
        assertStruct(
            "defmodule A do\n  defstruct a: [1, 2 | 3]\nend",
            "{:%{}, [], [__struct__: A, a: [1, {:|, [], [2, 3]}]]}",
        )

    fun testAQuoteDefaultsColumnIsDroppedBefore119() =
        assertStruct(
            "defmodule A do\n  defstruct a: {:quote, [line: 1, column: 2], [1]}\nend",
            "{:%{}, [], [__struct__: A, a: {:{}, [], [:quote, [line: 1, column: 2], [1]]}]}",
            "{:%{}, [], [__struct__: A, a: {:{}, [], [:quote, [line: 1], [1]]}]}",
        )

    /**
     * A `quote`-shaped default whose metadata isn't a list is escaped as any other tuple from 1.17; before it `meta/2`
     * can't read the metadata. The default is a term no node is written for, as a bound value's is.
     */
    fun testAQuoteShapedDefaultWithoutListMetadataIsATuple() {
        val term = Term.Tuple(listOf(Term.Atom("quote"), Term.Atom("x"), Term.List(listOf(Term.Integer(BigInteger.ONE)))))

        assertEquals(
            "1.16.3: null\n1.18.4: {:{}, [], [:quote, :x, [1]]}",
            listOf("1.16.3", "1.18.4").joinToString("\n") { version ->
                val s = Synthetic(Meta(TextRange(0, 0), Meta.Position(1, 1), Meta.Position(1, 1)))
                val fragment = s.atom("nil")
                val escape = QuoteEscape(ImportTermEscapeView, AstEscapeBuilder(s), { literalOf(fragment, it) }, ElixirLanguageLevel.of(version), exports)

                "$version: " + (escape.escape(term) as? EscapeResult.Built)?.value?.let { inspect(it.toOtp()) }
            },
        )
    }

    /**
     * `__struct__/0`'s body on each leg from 1.18, where it returns the escaped struct: [before119] on 1.18, [kept]
     * from 1.19.
     */
    private fun assertStruct(code: String, kept: String, before119: String = kept) {
        val legs = listOf("1.18.4", "1.19.5", "1.20.4")

        assertEquals(
            legs.joinToString("\n") { "$it: " + if (it == "1.18.4") before119 else kept },
            legs.joinToString("\n") { "$it: ${struct(code, it)}" },
        )
    }

    private fun struct(code: String, version: String): String {
        val level = ElixirLanguageLevel.of(version)
        val loaded = mutableListOf<ModuleClauses>()
        val observer = object : ExpansionObserver {
            override fun entering(node: ElixirAst, state: ExState, env: Env) {}

            override fun clausesLoaded(module: String, clauses: ModuleClauses?) {
                clauses?.let { loaded += it }
            }
        }

        Expander.expandFile(lower(code, level), Env.empty(level, kernel), level, exports, structs, observer)

        val clause = loaded.single().definitions.getValue(NameArity("__struct__", 0)).clauses?.single()

        return clause?.body?.let { inspect(it.toOtp()) } ?: "unknown"
    }

    fun testEnforcedKeysThatAreNotKnownStopTheModule() =
        assertEvery(
            "defmodule A do\n  @enforce_keys Foo.keys()\n  defstruct [:a]\nend",
            "module Elixir.A stopped `defstruct [:a]`$TABLE_AT_3",
        )

    fun testADefstructInAFunctionStopsTheModule() =
        assertEvery(
            "defmodule A do\n  def f, do: defstruct([:a])\nend",
            "module Elixir.A stopped `defstruct([:a])` | def f/0 line 2 clauses 1",
        )

    /** The module stops where the walk lost Elixir's path: at the `if` whose subject the evaluator can't produce. */
    fun testADefstructInABranchStopsTheModule() {
        val branch = "if Foo.ok() do\n    defstruct [:a]\n  else\n    defstruct [:b]\n  end"

        assertEvery(
            "defmodule A do\n  $branch\nend",
            "module Elixir.A stopped `$branch` | def __struct__/0 line 5 clauses 2 | def __struct__/1 line 5 clauses 2",
        )
    }

    fun testADefstructThatMayNotRunStopsTheModule() {
        val branch = "if Foo.ok() do\n    defstruct [:a]\n  end"

        assertEvery("defmodule A do\n  $branch\nend", "module Elixir.A stopped `$branch`$TABLE_AT_3")
    }

    fun testDefaultsThatNameModulesAreAtoms() =
        assertEvery(
            "defmodule A do\n  alias Foo.Bar\n  defstruct a: Bar, b: [Bar, __MODULE__], c: {Bar, 1}, d: %{}\nend",
            "module Elixir.A compiled$TABLE_AT_3",
        )

    /** Before the struct is escaped in the output, the compiler expands the fun in the definitions that read it. */
    fun testAnExternalCaptureIsAValidDefault() =
        assertSplit(
            "defmodule A do\n  defstruct f: &String.upcase/1\nend",
            "1.18.0-rc.0",
            "module Elixir.A stopped `defstruct f: &String.upcase/1`$TABLE",
            "module Elixir.A compiled$TABLE",
        )

    /** A capture that isn't `&Mod.f/n` may be of an imported function, which escapes, or a local one, which doesn't. */
    fun testAnyOtherCaptureStopsTheModule() =
        assertEvery(
            "defmodule A do\n  defstruct f: &String.upcase(&1)\nend",
            "module Elixir.A stopped `defstruct f: &String.upcase(&1)`$TABLE",
        )

    fun testASecondDefstructRaises() =
        assertEvery(
            "defmodule A do\n  defstruct [:a]\n  defstruct [:b]\nend",
            "module Elixir.A raised struct_twice `defstruct [:b]`$TABLE",
        )

    fun testFieldsThatAreNotAListRaise() =
        assertEvery(
            "defmodule A do\n  defstruct :a\nend",
            "module Elixir.A raised struct_fields_not_list `defstruct :a`",
        )

    fun testAFieldNameThatIsNotAnAtomRaises() =
        assertEvery(
            "defmodule A do\n  defstruct [\"a\"]\nend",
            "module Elixir.A raised struct_field_not_atom `defstruct [\"a\"]`",
        )

    fun testAnInvalidDefaultRaises() =
        assertEvery(
            "defmodule A do\n  defstruct a: fn -> 1 end\nend",
            "module Elixir.A raised struct_invalid_default `defstruct a: fn -> 1 end`",
        )

    fun testTheStructKeyIsReservedFrom1_18() =
        assertSplit(
            "defmodule A do\n  defstruct [:__struct__]\nend",
            "1.18.0-rc.0",
            "module Elixir.A compiled$TABLE",
            "module Elixir.A raised struct_reserved_key `defstruct [:__struct__]`",
        )

    fun testEnforcedKeysThatAreNotAtomsRaise() =
        assertEvery(
            "defmodule A do\n  @enforce_keys [\"a\"]\n  defstruct [:a]\nend",
            "module Elixir.A raised enforce_key_not_atom `defstruct [:a]`",
        )

    fun testEnforcedKeysThatAreNotInTheFieldsRaiseFrom1_12() =
        assertSplit(
            "defmodule A do\n  @enforce_keys [:b]\n  defstruct [:a]\nend",
            "1.12.0-rc.0",
            "module Elixir.A compiled$TABLE_AT_3",
            "module Elixir.A raised enforce_keys_not_defined `defstruct [:a]`",
        )

    fun testTheEventsOfAPlainStruct() = assertEvents("defmodule A do\n  defstruct [:a]\nend", enforced = false)

    fun testDefaultsAddNoEvents() =
        assertEvents("defmodule A do\n  defstruct a: 1, b: [1, 2], c: %{x: :y}\nend", enforced = false)

    fun testTheEventsOfAStructWithEnforcedKeys() =
        assertEvents("defmodule A do\n  @enforce_keys [:a]\n  defstruct [:a]\nend", enforced = true)

    /** The dispatches of `defstruct`'s expansion and of the two definitions it stores, as Elixir traces them. */
    private fun assertEvents(code: String, enforced: Boolean) {
        val start = if (enforced) StructEvents.AT else StructEvents.DEFSTRUCT

        assertEquals(
            LEVELS.joinToString("\n") { "$it: ${expected(it, enforced)}" },
            LEVELS.joinToString("\n") { "$it: " + events(code, it).dropWhile { event -> event != start } },
        )
    }

    private fun expected(version: String, enforced: Boolean) =
        StructEvents.output(version, StructEvents.DEFSTRUCT, emptyList(), enforced) + StructEvents.bodies(version, enforced)

    override fun expandAndRender(code: String, version: String): String {
        val level = ElixirLanguageLevel.of(version)
        val file = Expander.expandFile(lower(code, level), Env.empty(level, kernel), level, exports, structs)

        return file.modules.joinToString("\n") { ModuleRendering.render(code, it) }
    }

    private companion object {
        const val TABLE = " | def __struct__/0 line 2 clauses 1 | def __struct__/1 line 2 clauses 1"
        const val TABLE_AT_3 = " | def __struct__/0 line 3 clauses 1 | def __struct__/1 line 3 clauses 1"
    }
}
