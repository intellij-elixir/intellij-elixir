package org.elixir_lang.model.psi.rename

import com.intellij.openapi.command.WriteCommandAction
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.renameTargetAtCaret
import org.elixir_lang.code_insight.renameUsageRangesAtCaret
import org.elixir_lang.code_insight.singleTargetPsiUsagesAtCaret
import java.io.File

/**
 * Specification-driven rename matrix: for every legal Elixir construct a user could reasonably
 * expect Rename (Shift+F6) to handle, a rename is attempted FROM EVERY occurrence of the name in
 * the fixture - the declaration and every usage form alike - and every attempt must produce the
 * same golden `*_after.ex` result. That encodes the user's mental model: rename is symmetric; it
 * should not matter which occurrence the caret is on.
 *
 * These tests deliberately encode EXPECTED user-facing behavior, not current implementation
 * behavior - some are red by design. A failing caret position is a functionality gap to triage,
 * not necessarily a test bug. Each scenario aggregates all its caret positions and reports every
 * failing one, so a single run maps the whole gap surface.
 *
 * Mechanics are user-facing only: the file is opened, the caret is placed on an occurrence (as a
 * click would), and the rename runs through the platform pipeline (see
 * [org.elixir_lang.code_insight.renameTargetAtCaret]); assertions compare resulting text.
 */
class RenameMatrixTest : PlatformTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/model/psi/rename/matrix"

    // -- Modules ----------------------------------------------------------------------------

    /** `defmodule`, `alias`, `require`, `import`, `use`, qualified call. */
    fun testModuleDirect() = doTestFromEveryOccurrence("module_direct", "Renamee", "Fresh", expectedCarets = 6)

    /** `defprotocol`, `defimpl`, `alias ... as:` and a call through the protocol. */
    fun testModuleProtocol() = doTestFromEveryOccurrence("module_protocol", "Renamee", "Fresh", expectedCarets = 4)

    /** Nested name `Outer.Renamee`; a bare aliased reference follows the rename. */
    fun testModuleNested() = doTestFromEveryOccurrence("module_nested", "Renamee", "Outer.Fresh", expectedCarets = 3)

    /** A module declared inside another, its bare use there and its qualified use elsewhere. */
    fun testModuleNestedInModule() =
        doTestFromEveryOccurrence("module_nested_in_module", "Renamee", "Fresh", expectedCarets = 3)

    /** Multi-alias `alias Grouped.{Renamee, Sibling}`. */
    fun testModuleMultiAlias() =
        doTestFromEveryOccurrence("module_multi_alias", "Renamee", "Grouped.Fresh", expectedCarets = 3)

    /** Struct owner: `%Renamee{}` build and match forms. */
    fun testModuleStruct() = doTestFromEveryOccurrence("module_struct", "Renamee", "Fresh", expectedCarets = 3)

    /** `@behaviour Renamee` reference. */
    fun testModuleBehaviourReference() =
        doTestFromEveryOccurrence("module_behaviour", "Renamee", "Fresh", expectedCarets = 2)

    /** `defimpl ..., for: Renamee` and a struct pattern inside the impl. */
    fun testModuleDefimplFor() =
        doTestFromEveryOccurrence("module_defimpl_for", "Renamee", "Fresh", expectedCarets = 3)

    /** `alias AliasAs.Renamee, as: R` - the `as` alias `R` must survive unchanged. */
    fun testModuleAliasAs() =
        doTestFromEveryOccurrence("module_alias_as", "Renamee", "AliasAs.Fresh", expectedCarets = 2)

    /**
     * Renaming `One.Two.Three` - a MULTI-SEGMENT member `Two.Three` of `alias One.{Two.Three,
     * Four}` - rewrites the member relative to the group qualifier (`Two.Fresh`) and the bare
     * `Three.a()` reference to `Fresh.a()`.
     */
    fun testModuleGroupDeepMember() =
        doTestFromEveryOccurrence("module_group_deep_member", "Three", "One.Two.Fresh", expectedCarets = 3)

    /** Renaming `One.Four`, the plain sibling member of the same group, rewrites only its slot. */
    fun testModuleGroupSibling() =
        doTestFromEveryOccurrence("module_group_sibling", "Four", "One.Fresh", expectedCarets = 3)

    // Hierarchical namespace rename (caret on a qualifier segment renames the prefix as a
    // package would) is planned but not implemented - spec and design live in
    // IdeaProjects/docs/ideas.md ("Hierarchical module rename"). Its scenarios
    // (module_namespace_segment / module_namespace_root) will be restored with that feature.

    // -- Functions and macros ---------------------------------------------------------------

    /** `@spec`, `def`, unqualified call, qualified call. */
    fun testFunctionEverySite() = doTestFromEveryOccurrence("function_every_site", "renamee", "fresh", expectedCarets = 4)

    /** Multiple clauses of one function plus a recursive call. */
    fun testFunctionMultiClause() =
        doTestFromEveryOccurrence("function_multi_clause", "renamee", "fresh", expectedCarets = 3)

    /** Local `&renamee/1` and remote `&Mod.renamee/1` captures. */
    fun testFunctionCapture() = doTestFromEveryOccurrence("function_capture", "renamee", "fresh", expectedCarets = 3)

    /** Pipe calls `x |> renamee() |> Mod.renamee()` (arity is shifted by the pipe). */
    fun testFunctionPipe() = doTestFromEveryOccurrence("function_pipe", "renamee", "fresh", expectedCarets = 3)

    /** A def with a default argument spans two arities; both call sites rename. */
    fun testFunctionDefaultArgs() =
        doTestFromEveryOccurrence("function_default_args", "renamee", "fresh", expectedCarets = 3)

    /**
     * Two same-named functions of different arity are DIFFERENT symbols: renaming from a
     * `renamee/1` occurrence (spec, def, call) must leave `renamee/2` untouched, and renaming
     * from a `renamee/2` occurrence (its def or its call - carets #2 and #4) must leave
     * `renamee/1` untouched, hence the per-caret golden overrides.
     */
    fun testFunctionArityFamilies() =
        doTestFromEveryOccurrence(
            "function_arity_families", "renamee", "fresh", expectedCarets = 5,
            goldenOverrides = mapOf(
                2 to "function_arity_families_after_arity2.ex", // caret on `def renamee(x, y)`
                4 to "function_arity_families_after_arity2.ex"  // caret on `renamee(x, x)`
            )
        )

    /** `defp` and its call. */
    fun testFunctionPrivate() = doTestFromEveryOccurrence("function_private", "renamee", "fresh", expectedCarets = 2)

    /** `defmacro` and its call through `import`. */
    fun testMacroImport() = doTestFromEveryOccurrence("macro_import", "renamee", "fresh", expectedCarets = 2)

    /** `defdelegate renamee(x), to: Target` stays in sync with the target def. */
    fun testFunctionDefdelegate() =
        doTestFromEveryOccurrence("function_defdelegate", "renamee", "fresh", expectedCarets = 2)

    /** `apply(Mod, :renamee, [x])` atom follows the function. */
    fun testFunctionApplyAtom() =
        doTestFromEveryOccurrence("function_apply_atom", "renamee", "fresh", expectedCarets = 2)

    /** An atom follows only the function of its own arity: `:renamee` in `apply(Mod, :renamee, [x, y])` is `renamee/2`. */
    fun testFunctionApplyAtomArityFamilies() =
        doTestFromEveryOccurrence(
            "function_apply_atom_arity_families", "renamee", "fresh", expectedCarets = 4,
            goldenOverrides = mapOf(
                1 to "function_apply_atom_arity_families_after_arity2.ex", // caret on `def renamee(x, y)`
                3 to "function_apply_atom_arity_families_after_arity2.ex"  // caret on `:renamee` in `[x, y]`
            )
        )

    /** `defguard` and its use in a `when` clause. */
    fun testFunctionDefguard() = doTestFromEveryOccurrence("function_defguard", "renamee", "fresh", expectedCarets = 2)

    // -- Callbacks --------------------------------------------------------------------------

    /** `@callback` and the implementing def - renaming from either keeps them in sync. */
    fun testCallbackEverySite() =
        doTestFromEveryOccurrence("callback_every_site", "renamee", "fresh", expectedCarets = 2)

    /** `@macrocallback` and the implementing defmacro. */
    fun testMacrocallbackEverySite() =
        doTestFromEveryOccurrence("macrocallback_every_site", "renamee", "fresh", expectedCarets = 2)

    /** `@callback`, the default def inside `__using__`, and the `defoverridable` entry. */
    fun testCallbackDefoverridable() =
        doTestFromEveryOccurrence("callback_defoverridable", "renamee", "fresh", expectedCarets = 3)

    // -- Protocols --------------------------------------------------------------------------

    /** `defprotocol` def, `defimpl` def, and dispatching call site. */
    fun testProtocolEverySite() =
        doTestFromEveryOccurrence("protocol_every_site", "renamee", "fresh", expectedCarets = 3)

    /**
     * Renaming a protocol function from any of its occurrences, the protocol's `def`, an implementing head, a `@spec`,
     * a dispatching call or a call to an implementation, renames every use of each implementing definition: its local
     * calls, capture and `@spec`, and `Size.List.size(x)`.
     */
    fun testProtocolImplementationCalls() =
        doTestFromEveryOccurrence("implementation_calls_protocol", "size", "fresh", expectedCarets = 9)

    /** The same for a callback, from any of its occurrences. */
    fun testCallbackImplementationCalls() =
        doTestFromEveryOccurrence("implementation_calls_callback", "size", "fresh", expectedCarets = 6)

    /**
     * One protocol function implemented by a clause in one `defimpl` and by a `defdelegate` with `as:` in another is one
     * rename from the protocol's `def`, either `defimpl`'s clause or its local call, and `Size.size(x)`. A caret on the
     * `defdelegate`'s head or its local call is its `to:` function's rename until #4425 is fixed.
     */
    fun testProtocolClauseAndDelegationImplementationCalls() =
        doTestFromEveryOccurrence(
            "implementation_calls_protocol_clause_and_delegation",
            "size",
            "fresh",
            expectedCarets = 6,
            carets = setOf(0, 1, 2, 5)
        )

    /** The same when the `defdelegate`'s `to:` does not resolve, so its head and local call are its own function's. */
    fun testProtocolUnresolvedDelegationImplementationCalls() =
        doTestFromEveryOccurrence("implementation_calls_protocol_unresolved_delegation", "size", "fresh", expectedCarets = 6)

    /**
     * A `defp` in a `defimpl` that does not implement the protocol function of its name is renamed with its call, and a
     * call `Size.size(x)` that dispatches to the protocol and an atom in `apply(Size, :size, [x])` keep their name.
     */
    fun testDefimplHelperKeepsProtocolCall() =
        doTestFromEveryOccurrence("defimpl_helper_protocol_call", "size", "fresh", expectedCarets = 6, carets = setOf(2, 3))

    /**
     * Find Usages and Rename from the `defdelegate`'s head or its local call, which target its `to:` function, find one
     * set of ranges.
     */
    fun testProtocolClauseAndDelegationFindUsagesAgreesWithRename() {
        val before = loadFixture("implementation_calls_protocol_clause_and_delegation.ex")
        val occurrences = wordOccurrences(before, "size")
        for (index in listOf(3, 4)) {
            myFixture.configureByText("agreement_caret$index.ex", before)
            myFixture.editor.caretModel.moveToOffset(occurrences[index] + 1)

            val found = myFixture.singleTargetPsiUsagesAtCaret(project).map { it.file.virtualFile.path to it.range }.toSet()
            val renamed = myFixture.renameUsageRangesAtCaret().map { (file, range) -> file.virtualFile.path to range }.toSet()

            assertTrue("Rename finds ranges from caret #$index", renamed.isNotEmpty())
            assertEquals("Find Usages and Rename agree from caret #$index", renamed, found)
        }
    }

    /**
     * A `defdelegate` without `as:` in a `defimpl` keeps its head, so its spec, `@compile inline:` key and local call
     * keep their text, though a sibling `defimpl` defines the same function and is renamed.
     */
    fun testProtocolPlainDelegationSiblingKeepsCalls() =
        doTestFromEveryOccurrence(
            "implementation_calls_protocol_plain_delegation", "size", "fresh", expectedCarets = 7, carets = setOf(0)
        )

    /** A `defdelegate` with `as:` implementing a protocol function is renamed with its spec, key, calls and capture. */
    fun testProtocolDelegationImplementationCalls() =
        doTestFromEveryOccurrence(
            "implementation_calls_protocol_delegation", "size", "fresh", expectedCarets = 7, carets = setOf(0, 6)
        )

    /** The same for a callback implemented by a `defdelegate`, including an `import ... only:` key. */
    fun testCallbackDelegationImplementationCalls() =
        doTestFromEveryOccurrence(
            "implementation_calls_callback_delegation", "area", "surface", expectedCarets = 7, carets = setOf(0)
        )

    /** A `defdelegate` whose `to:` does not resolve is a function of its own module, with its calls. */
    fun testFunctionDefdelegateUnresolved() =
        doTestFromEveryOccurrence("function_defdelegate_unresolved", "run", "fresh", expectedCarets = 5)

    /**
     * A `defp` in a `defimpl` implements nothing, so it is renamed with every clause and call in its own `defimpl`, and
     * not with a `defp` of the same name in another.
     */
    fun testDefimplHelperClauses() =
        doTestFromEveryOccurrence("defimpl_helper_clauses", "helper", "fresh", expectedCarets = 5, carets = setOf(0, 1, 2))

    /** A public `def` in a `defimpl` that the protocol does not declare is renamed with its calls, not with a sibling's. */
    fun testDefimplPublicHelper() =
        doTestFromEveryOccurrence("defimpl_public_helper", "twice", "fresh", expectedCarets = 4, carets = setOf(0, 1))

    /** A public `def` of an arity the protocol does not declare implements nothing, so a sibling `defimpl`'s is kept. */
    fun testDefimplExtraArity() =
        doTestFromEveryOccurrence("defimpl_extra_arity", "size", "fresh", expectedCarets = 5, carets = setOf(2))

    /** Renaming a protocol function renames the other clauses of every `defimpl`'s default-argument header, not one's. */
    fun testDefimplDefaultArgument() =
        doTestFromEveryOccurrence("defimpl_default_argument", "size", "fresh", expectedCarets = 5, carets = setOf(0, 1, 3))

    /**
     * An atom in `apply(Size, :size, [x])` names the protocol function, so a rename from the protocol's `def`, an
     * implementing head, the atom or `Size.size(x)` renames the atom with the rest.
     */
    fun testProtocolImplementationCallsAtom() =
        doTestFromEveryOccurrence("implementation_calls_protocol_atom", "size", "fresh", expectedCarets = 4)

    /** An atom in `apply(Size.List, :size, [x])` names an implementation, so it is renamed with the protocol function. */
    fun testProtocolImplementationAtom() =
        doTestFromEveryOccurrence("implementation_calls_protocol_implementation_atom", "size", "fresh", expectedCarets = 4)

    /** An atom in `apply(Impl, :size, [x])` names a callback's implementation, so it is renamed with the callback. */
    fun testCallbackImplementationAtom() =
        doTestFromEveryOccurrence("implementation_calls_callback_atom", "size", "fresh", expectedCarets = 7)

    /** The same for a protocol function no `defimpl` implements yet. */
    fun testProtocolUnimplementedAtom() =
        doTestFromEveryOccurrence("implementation_calls_protocol_atom_unimplemented", "size", "fresh", expectedCarets = 3)

    /** And only the protocol function of the atom's arity. */
    fun testProtocolUnimplementedAtomArityFamilies() =
        doTestFromEveryOccurrence(
            "implementation_calls_protocol_atom_arity_families", "size", "fresh", expectedCarets = 4,
            goldenOverrides = mapOf(
                1 to "implementation_calls_protocol_atom_arity_families_after_arity2.ex", // caret on `def size(data, options)`
                3 to "implementation_calls_protocol_atom_arity_families_after_arity2.ex"  // caret on `:size` in `[data, options]`
            )
        )

    /**
     * A callback implemented by a head with a default argument: its separate clauses of the longer arity are renamed
     * with the head and its calls, though they implement no callback themselves.
     */
    fun testCallbackImplementationCallsDefaults() =
        doTestFromEveryOccurrence(
            "implementation_calls_callback_defaults", "run", "fresh", expectedCarets = 6, carets = setOf(0, 1, 4, 5)
        )

    /** `Size.size(x)` is a call to the protocol and, through the module name, a call to its implementations: one usage. */
    fun testProtocolImplementationCallsAreEachRenamedOnce() = assertEachRangeRenamedOnce("implementation_calls_protocol")

    fun testCallbackImplementationCallsAreEachRenamedOnce() = assertEachRangeRenamedOnce("implementation_calls_callback")

    // -- Module attributes ------------------------------------------------------------------

    /** Declaration and read. */
    fun testAttributeEverySite() =
        doTestFromEveryOccurrence("attribute_every_site", "renamee", "fresh", expectedCarets = 2)

    /** Re-declared (accumulated/overridden) attribute: both declarations plus the read. */
    fun testAttributeAccumulate() =
        doTestFromEveryOccurrence("attribute_accumulate", "renamee", "fresh", expectedCarets = 3)

    // -- Variables --------------------------------------------------------------------------

    /** Declaration and reads. */
    fun testVariableEverySite() =
        doTestFromEveryOccurrence("variable_every_site", "renamee", "fresh", expectedCarets = 3)

    /** Function parameter and its reads. */
    fun testVariableParameter() =
        doTestFromEveryOccurrence("variable_parameter", "renamee", "fresh", expectedCarets = 3)

    /** Rebinding chain `renamee = x; renamee = renamee + 1; renamee`. */
    fun testVariableRebind() = doTestFromEveryOccurrence("variable_rebind", "renamee", "fresh", expectedCarets = 4)

    /** Pin operator `^renamee` in a case pattern. */
    fun testVariablePin() = doTestFromEveryOccurrence("variable_pin", "renamee", "fresh", expectedCarets = 2)

    /** Binding introduced by a case pattern and used in the clause body. */
    fun testVariableCasePattern() =
        doTestFromEveryOccurrence("variable_case_pattern", "renamee", "fresh", expectedCarets = 2)

    /** Parameter captured by a closure (`fn -> renamee + 1 end`). */
    fun testVariableClosure() = doTestFromEveryOccurrence("variable_closure", "renamee", "fresh", expectedCarets = 2)

    /** Comprehension generator binding `for renamee <- list`. */
    fun testVariableComprehension() =
        doTestFromEveryOccurrence("variable_comprehension", "renamee", "fresh", expectedCarets = 2)

    /** A read in a `when` guard is a read of the binding outside the clause, not a binding of the clause's pattern. */
    fun testVariableGuardRead() =
        doTestFromEveryOccurrence("variable_guard_read", "renamee", "fresh", expectedCarets = 2)

    /** An `fn` written as a `cond` clause's body binds its parameter like any other `fn`. */
    fun testVariableCondFnParameter() =
        doTestFromEveryOccurrence("variable_cond_fn_parameter", "renamee", "fresh", expectedCarets = 2)

    /** A definition inside a matched `quote` binds its parameter for its own body, not for the match. */
    fun testVariableQuotedDefParameter() =
        doTestFromEveryOccurrence("variable_quoted_def_parameter", "renamee", "fresh", expectedCarets = 2)

    /**
     * A rebinding inside an `fn` BODY is the same variable as the one it rebinds, unlike an `fn`
     * PARAMETER of the same name, which shadows it.
     */
    fun testVariableAnonymousFunctionBodyRebinding() =
        doTestFromEveryOccurrence("variable_fn_body_rebinding", "renamee", "fresh", expectedCarets = 5)

    /** The same for a `case` clause body: only the clause PATTERN binds afresh. */
    fun testVariableCaseClauseBodyRebinding() =
        doTestFromEveryOccurrence("variable_case_clause_rebinding", "renamee", "fresh", expectedCarets = 5)

    /** `with {:ok, renamee} <- ...` binding and body use. */
    fun testVariableWith() = doTestFromEveryOccurrence("variable_with", "renamee", "fresh", expectedCarets = 2)

    /**
     * One name bound twice across two map patterns of the same clause head, where the second
     * binding matches against the first rather than introducing a new one. Both occurrences are the
     * same variable and must rename together from either caret.
     */
    fun testVariableMapPatternRepeated() =
        doTestFromEveryOccurrence("variable_map_pattern_repeated", "renamee", "fresh", expectedCarets = 2)

    /** A binding inside string interpolation, `_ = "#{renamee = 1}"`, and its read after the string. */
    fun testVariableInterpolationBinding() =
        doTestFromEveryOccurrence("variable_interpolation_binding", "renamee", "fresh", expectedCarets = 2)

    /** A binding on the left of an inner match that sits on the right of an outer one, `_ = (renamee = 1)`. */
    fun testVariableNestedMatchBinding() =
        doTestFromEveryOccurrence("variable_nested_match_binding", "renamee", "fresh", expectedCarets = 2)

    /** A `for` generator read inside a generated test's name, `test "handles #{renamee}"`, and in its body. */
    fun testVariableComprehensionTestName() =
        doTestFromEveryOccurrence("variable_comprehension_test_name", "renamee", "fresh", expectedCarets = 3)

    /** A case pattern binding inside `result = case ... do ... end`: a pattern, although right of a match. */
    fun testVariableCasePatternInMatch() =
        doTestFromEveryOccurrence("variable_case_pattern_in_match", "renamee", "fresh", expectedCarets = 2)

    /** An `fn` parameter inside `f = fn renamee -> ... end`. */
    fun testVariableFnParameterInMatch() =
        doTestFromEveryOccurrence("variable_fn_parameter_in_match", "renamee", "fresh", expectedCarets = 2)

    /** `%{a: [_ | _] = renamee} = map`: the inner match sits in the outer pattern, so both its sides bind. */
    fun testVariableMatchInsidePattern() =
        doTestFromEveryOccurrence("variable_match_inside_pattern", "renamee", "fresh", expectedCarets = 2)

    /** `def run(%{} = renamee)`: a match in a definition head binds both sides. */
    fun testVariableMatchInsideHead() =
        doTestFromEveryOccurrence("variable_match_inside_head", "renamee", "fresh", expectedCarets = 2)

    /** The enumerable of a generator under a match, `result = for item <- renamee`, is a read of the parameter. */
    fun testVariableComprehensionEnumerableInMatch() =
        doTestFromEveryOccurrence("variable_comprehension_enumerable_in_match", "renamee", "fresh", expectedCarets = 2)

    /** A `with` clause's argument under a match, `result = with {:ok, v} <- fetch(renamee)`, is a read too. */
    fun testVariableWithArgumentInMatch() =
        doTestFromEveryOccurrence("variable_with_argument_in_match", "renamee", "fresh", expectedCarets = 2)

    /** A binding inside a list that is a statement on its own, `[renamee = 1]`, and its read after it. */
    fun testVariableBareListBinding() =
        doTestFromEveryOccurrence("variable_bare_list_binding", "renamee", "fresh", expectedCarets = 2)

    /** A binding inside a string that is a statement on its own, `"#{renamee = 1}"`. */
    fun testVariableBareStringBinding() =
        doTestFromEveryOccurrence("variable_bare_string_binding", "renamee", "fresh", expectedCarets = 2)

    /** A binding inside a call's arguments, `IO.puts(renamee = 1)`, and its read after the call. */
    fun testVariableCallArgumentBinding() =
        doTestFromEveryOccurrence("variable_call_argument_binding", "renamee", "fresh", expectedCarets = 2)

    /** A read inside string interpolation in a call argument, `IO.puts("Hello #{renamee}")`. */
    fun testVariableInterpolationArgumentRead() =
        doTestFromEveryOccurrence("variable_interpolation_argument_read", "renamee", "fresh", expectedCarets = 2)

    /** Reads on the right of a match: bare, inside a list and inside string interpolation. */
    fun testVariableMatchRhsRead() =
        doTestFromEveryOccurrence("variable_match_rhs_read", "renamee", "fresh", expectedCarets = 4)

    // -- Types ------------------------------------------------------------------------------

    /** `@type` declaration and its references inside a `@spec`. */
    fun testTypeEverySite() = doTestFromEveryOccurrence("type_every_site", "renamee", "fresh", expectedCarets = 3)

    /** `@opaque` declaration and a `@spec` reference. */
    fun testTypeOpaque() = doTestFromEveryOccurrence("type_opaque", "renamee", "fresh", expectedCarets = 2)

    /** Remote type references `RemoteTypeDef.renamee` from another module's spec. */
    fun testTypeRemote() = doTestFromEveryOccurrence("type_remote", "renamee", "fresh", expectedCarets = 3)

    /** A `@type` referenced from another `@type`'s body. */
    fun testTypeInType() = doTestFromEveryOccurrence("type_in_type", "renamee", "fresh", expectedCarets = 2)

    // -- Type variables ---------------------------------------------------------------------

    /** `@type box(renamee)` head parameter and its body use. */
    fun testTypeVariableEverySite() =
        doTestFromEveryOccurrence("type_variable_every_site", "renamee", "fresh", expectedCarets = 2)

    /** `@spec ... when renamee: term()` binding plus head and return uses. */
    fun testSpecWhenEverySite() =
        doTestFromEveryOccurrence("spec_when_every_site", "renamee", "fresh", expectedCarets = 3)

    // -- MFA atoms --------------------------------------------------------------------------

    /** `def`, MFA-tuple atom `:renamee`, and a plain call - all in sync from any caret. */
    fun testMfaEverySite() = doTestFromEveryOccurrence("mfa_every_site", "renamee", "fresh", expectedCarets = 3)

    // -- Harness ----------------------------------------------------------------------------

    /**
     * Renames [oldName] to [newName] once per occurrence of [oldName] in `[fixtureName].ex`,
     * with the caret placed on that occurrence, and asserts each attempt yields exactly
     * `[fixtureName]_after.ex` - or, for caret indices present in [goldenOverrides], the golden
     * named there (for fixtures where same-named occurrences are different symbols, e.g. two
     * arities of one function name). Aggregates all failing caret positions into one assertion so
     * a run reports the complete gap map for the scenario. [expectedCarets] guards the fixture
     * itself: if the occurrence count drifts, the matrix silently loses coverage. [carets] limits the attempts to
     * those occurrence indices, for a fixture where a caret on some occurrence is a different rename target.
     */
    private fun doTestFromEveryOccurrence(
        fixtureName: String,
        oldName: String,
        newName: String,
        expectedCarets: Int,
        goldenOverrides: Map<Int, String> = emptyMap(),
        carets: Set<Int>? = null
    ) {
        val before = loadFixture("$fixtureName.ex")
        val defaultAfter = loadFixture("${fixtureName}_after.ex")
        val occurrences = wordOccurrences(before, oldName)
        assertEquals(
            "$fixtureName.ex should contain exactly $expectedCarets occurrences of '$oldName'",
            expectedCarets,
            occurrences.size
        )

        val failures = mutableListOf<String>()
        occurrences.forEachIndexed { index, offset ->
            if (carets != null && index !in carets) return@forEachIndexed
            val after = goldenOverrides[index]?.let(::loadFixture) ?: defaultAfter
            myFixture.configureByText("${fixtureName}_caret$index.ex", before)
            val virtualFile = myFixture.file.virtualFile
            myFixture.editor.caretModel.moveToOffset(offset + 1)
            try {
                myFixture.renameTargetAtCaret(newName)
                val actual = myFixture.file.text
                if (actual != after) {
                    failures += "caret #$index (${describeCaret(before, offset)}): ${firstDifference(actual, after)}"
                }
            } catch (e: Throwable) {
                val reason = e.message?.lineSequence()?.firstOrNull() ?: e.javaClass.simpleName
                failures += "caret #$index (${describeCaret(before, offset)}): ${e.javaClass.simpleName}: $reason"
            } finally {
                // Each iteration must start from a clean project: a leftover (partially) renamed
                // copy would offer duplicate rename targets to the next iteration.
                WriteCommandAction.runWriteCommandAction(project) { virtualFile.delete(this) }
            }
        }

        if (failures.isNotEmpty()) {
            fail(
                "Renaming '$oldName' -> '$newName' in $fixtureName.ex failed from " +
                    "${failures.size} of ${carets?.size ?: occurrences.size} caret positions:\n\n" +
                    failures.joinToString("\n\n")
            )
        }
    }

    /**
     * The rename usages from the first occurrence of `size` in `[fixtureName].ex`, whose every occurrence the golden
     * renames, are one per occurrence: each file and range once.
     */
    private fun assertEachRangeRenamedOnce(fixtureName: String) {
        val before = loadFixture("$fixtureName.ex")
        val occurrences = wordOccurrences(before, "size")
        myFixture.configureByText("$fixtureName.ex", before)
        myFixture.editor.caretModel.moveToOffset(occurrences.first() + 1)

        val ranges = myFixture.renameUsageRangesAtCaret().map { (file, range) -> file.virtualFile.path to range }

        assertEquals("Each occurrence once, in $ranges", occurrences.size, ranges.size)
        assertEquals("Each file and range once, in $ranges", ranges.distinct(), ranges)
    }

    private fun loadFixture(name: String): String =
        File(testDataPath, name).readText().replace("\r\n", "\n")

    /** Start offsets of every whole-word occurrence of [word] (not part of a longer identifier). */
    private fun wordOccurrences(text: String, word: String): List<Int> =
        Regex("(?<![A-Za-z0-9_])${Regex.escape(word)}(?![A-Za-z0-9_?!])")
            .findAll(text)
            .map { it.range.first }
            .toList()

    private fun describeCaret(text: String, offset: Int): String {
        val line = text.substring(0, offset).count { it == '\n' } + 1
        val lineText = text.lines()[line - 1].trim()
        return "line $line: `$lineText`"
    }

    private fun firstDifference(actual: String, expected: String): String {
        val actualLines = actual.lines()
        val expectedLines = expected.lines()
        val index = (0 until maxOf(actualLines.size, expectedLines.size))
            .firstOrNull { actualLines.getOrNull(it) != expectedLines.getOrNull(it) }
            ?: return "texts differ only in trailing whitespace"
        return "first difference at line ${index + 1}: " +
            "expected `${expectedLines.getOrNull(index)}`, actual `${actualLines.getOrNull(index)}`"
    }
}
