package org.elixir_lang.expander

/**
 * Local, remote and anonymous calls in a module body, outside a pattern and in one, at every supported minor and at
 * the first tag of each version difference.
 */
class CallExpanderTest : ExpanderTestCase() {
    override val exports: Exports = CallFixtures.EXPORTS
    override val kernel: KernelImports = CallFixtures.KERNEL

    // Local calls

    fun testALocalCallWithNoImportIsUndefined() = assertEvery("foo(1)", "error undefined_function `foo(1)`")

    fun testALocalCallWithNoImportIsUndefinedInAPattern() =
        assertEvery("foo(x) = 1", "error undefined_function `foo(x)`")

    fun testALocalCallWithNoImportIsUndefinedInAGuard() =
        assertEvery("case 1 do\nx when foo(x) -> x\nend", "error undefined_function `foo(x)`")

    fun testASpecialFormNameOfAnotherShapeIsALocalCall() {
        assertEvery("cond(1, 2)", "error undefined_function `cond(1, 2)`")
        assertEvery("quote(1, 2, 3)", "error undefined_function `quote(1, 2, 3)`")
    }

    fun testAnImportedFunction() {
        assertEvery("abs(1)", "expanded {} next 0")
        assertEvery("self()", "expanded {} next 0")
    }

    fun testTheArgumentsOfAnImportedFunctionAreExpanded() =
        assertSplit("abs(y)", "1.15.0-rc.0", "error undefined_function `y`", "error undefined_var `y`")

    fun testAnImportedMacroIsOpaque() =
        assertEvery("if true, do: 1", "opaque imported_macro Elixir.Kernel.if/2 `if true, do: 1`")

    fun testTheArgumentsOfAMacroAreNotExpanded() =
        assertEvery("if y, do: 1", "opaque imported_macro Elixir.Kernel.if/2 `if y, do: 1`")

    fun testTwoImportsOfOneNameAndArityAreAmbiguous() =
        assertEvery("import M, only: [f: 1]\nimport :x3e, only: [f: 1]\nf(1)", "error ambiguous_call `f(1)`")

    fun testAVariableBeforeASignedArgumentIsAmbiguous() = assertEvery("x = 1\nx -1", "error op_ambiguity `x -1`")

    fun testANameBeforeASignedArgumentIsACall() = assertEvery("foo -1", "error undefined_function `foo -1`")

    /** `%`, `super` and the captures that look a function up have clauses of their own ahead of the local call's. */
    fun testTheSpecialFormsAheadOfTheLocalCallAreUnported() {
        assertEvery("%URI{}", "unported `%URI{}`")
        assertEvery("super()", "unported `super()`")
        assertEvery("super(1)", "unported `super(1)`")
        assertEvery("&super/1", "unported `&super/1`")
        assertEvery("&super(&1)", "unported `&super(&1)`")
        assertEvery("&abs/1", "unported `&abs/1`")
    }

    /** An interpolation in a pattern expands `Kernel.to_string/1`, a macro, unless its value is a binary. */
    fun testAnInterpolationInAPatternIsOpaque() =
        assertEvery("x = \"a\"\n\"#{x}\" = \"a\"", "opaque remote_macro Elixir.Kernel.to_string/1 `#{x}`")

    /** From 1.15 a bitstring specifier written as a name is expanded as a call of no arguments. */
    fun testABitstringSpecifierThatIsAMacro() {
        assertSplit(
            "<<x::binding>> = <<1>>",
            "1.15.0-rc.0",
            "error undefined_bittype `x::binding`",
            "opaque imported_macro Elixir.Kernel.binding/0 `binding`",
        )
        assertEvery("<<x::binding()>> = <<1>>", "opaque imported_macro Elixir.Kernel.binding/0 `binding()`")
    }

    fun testABitstringSpecifierThatIsAFunctionIsAnError() =
        assertEvery("<<x::self()>> = <<1>>", "error undefined_bittype `x::self()`")

    /** `Macro.expand/2` folds an imported `Kernel.+/1` or `-/1` over an integer, which is then a size. */
    fun testABitstringSpecifierThatFoldsToAnIntegerIsASize() {
        assertEvery("<<x::+8>> = <<1>>", "expanded {x:0} next 1")
        assertEvery("<<x::+8.0>> = <<1>>", "error undefined_bittype `x::+8.0`")
        assertEvery("<<x::+self()>> = <<1>>", "error undefined_bittype `x::+self()`")
        assertEvery("<<x::+(+8)>> = <<1>>", "expanded {x:0} next 1")
        assertEvery("<<x::+binding()>> = <<1>>", "opaque imported_macro Elixir.Kernel.binding/0 `binding()`")
    }

    /** Before 1.15 the call is what `_ in` reads; from 1.15 it is expanded once as a macro. */
    fun testARescueOfAMacro() =
        assertEvery(
            "try do\n1\nrescue\nbinding() -> 1\nend",
            "opaque imported_macro Elixir.Kernel.binding/0 `binding()`",
        )

    // Remote calls

    fun testARemoteCallOfAnInlinedFunction() = assertEvery("Integer.to_string(1)", "expanded {} next 0")

    fun testARemoteCallOfAFunction() {
        assertEvery("Map.get(%{}, :a)", "expanded {} next 0")
        assertEvery(":lists.reverse([])", "expanded {} next 0")
    }

    fun testARemoteCallOfAModuleThatIsNotLoadedIsAFunction() = assertEvery("Foo.bar(1)", "expanded {} next 0")

    /** From 1.13 an unrequired module's macros aren't read. */
    fun testARemoteCallOfAModuleWhoseExportsCannotBeRead() =
        assertSplit("U.f(1)", "1.13.0-rc.0", "unported `U.f(1)`", "expanded {} next 0")

    fun testTheArgumentsOfARemoteCallAreExpanded() =
        assertSplit("Foo.bar(y)", "1.15.0-rc.0", "error undefined_function `y`", "error undefined_var `y`")

    fun testARemoteCallOfARequiredMacroIsOpaque() {
        assertEvery(
            "require Integer\nInteger.is_odd(1)",
            "opaque remote_macro Elixir.Integer.is_odd/1 `Integer.is_odd(1)`",
        )
        assertEvery("Kernel.if(true, do: 1)", "opaque remote_macro Elixir.Kernel.if/2 `Kernel.if(true, do: 1)`")
    }

    /** Before 1.12.2 the deprecation check loads the module first; until 1.13 the node's loaded modules decide. */
    fun testARemoteCallOfAMacroThatIsNotRequired() =
        assertLevels(
            "Record.is_record(1)",
            listOf("1.11.4", "1.12.1", "1.12.2", "1.12.3", "1.13.0-rc.0", "1.13.4", "1.20.4"),
        ) { version ->
            when {
                isBefore(version, "1.12.2") -> "error unrequired_module `Record.is_record(1)`"
                isBefore(version, "1.13.0-rc.0") -> "unported `Record.is_record(1)`"
                else -> "expanded {} next 0"
            }
        }

    fun testARemoteCallOfASpecialFormIsACallOfAFunction() = assertEvery("Kernel.alias(Foo)", "expanded {} next 0")

    fun testAVariableReceiverIsARunTimeCall() = assertEvery("x = 1\nx.foo()", "expanded {x:0} next 1")

    fun testATupleReceiverIsARunTimeCall() {
        assertEvery("{1, 2}.foo()", "expanded {} next 0")
        assertEvery("%{}.foo()", "expanded {} next 0")
        assertEvery("abs(1).foo()", "expanded {} next 0")
    }

    fun testAReceiverThatIsNeitherAnAtomNorATupleIsInvalid() {
        assertEvery("__DIR__.foo()", "error invalid_call `__DIR__.foo()`")
        assertEvery("__ENV__.line.foo()", "error invalid_call `__ENV__.line.foo()`")
        assertEvery("__ENV__.requires.foo()", "error invalid_call `__ENV__.requires.foo()`")
        assertEvery("String.Chars.to_string(\"a\").foo()", "error invalid_call `String.Chars.to_string(\"a\").foo()`")
    }

    fun testASignedNumberReceiverIsANumberFrom1_16() {
        assertSplit("(-1).foo()", "1.16.0-rc.0", "expanded {} next 0", "error invalid_call `(-1).foo()`")
        assertSplit("(+1.0).foo()", "1.16.0-rc.0", "expanded {} next 0", "error invalid_call `(+1.0).foo()`")
    }

    fun testClausesGivenToARemoteCall() =
        assertSplit(
            "Foo.bar do\nx -> x\nend",
            "1.18.0-rc.0",
            "error invalid_clauses `Foo.bar do\nx -> x\nend`",
            "error unhandled_arrow_op `x -> x`",
        )

    /** Only a call inside a function checks a local call's clauses. */
    fun testClausesGivenToALocalCall() =
        assertEvery("foo do\nx -> x\nend", "error undefined_function `foo do\nx -> x\nend`")

    // Remote calls in a pattern

    fun testAnAppendToAStaticListInAPattern() {
        assertEvery("l = [1, 2]\n[1] ++ x = l", "expanded {l:0 x:1} next 2")
        assertEvery("l = [1, 2]\n[] ++ x = l", "expanded {l:0 x:1} next 2")
        assertEvery("l = [1, 2]\n[1 | [2]] ++ x = l", "expanded {l:0 x:1} next 2")
    }

    fun testAnAppendToAnythingElseInAPatternIsInvalid() {
        assertEvery("l = [1, 2]\nx ++ [1] = l", "error invalid_match_append `x ++ [1]`")
        assertEvery("l = [1, 2]\n[1 | t] ++ x = l", "error invalid_match_append `[1 | t] ++ x`")
    }

    /** Up to 1.17 each argument reads only what was bound before the call, so the second `x` is a new variable. */
    fun testTheArgumentsOfAnAppendInAPattern() =
        assertSplit("[x] ++ x = [[1], 2]", "1.18.0-rc.0", "expanded {x:1} next 2", "expanded {x:0} next 1")

    fun testAnyOtherRemoteCallInAPatternIsInvalid() {
        assertEvery("Integer.to_string(x) = \"1\"", "error invalid_match `Integer.to_string(x)`")
        assertEvery("x + 1 = 2", "error invalid_match `x + 1`")
        assertEvery("x = 1\nx.foo() = 2", "error invalid_match `x.foo()`")
    }

    fun testASignedNumberInAPattern() {
        assertEvery("-1 = -1", "expanded {} next 0")
        assertEvery("+1 = 1", "expanded {} next 0")
    }

    /** From 1.18 only a sign written before a number folds in a pattern. */
    fun testASignedSignedNumberInAPattern() =
        assertSplit("-(-1) = 1", "1.18.0-rc.0", "expanded {} next 0", "error invalid_match `-(-1)`")

    // Anonymous calls

    fun testAnAnonymousCall() =
        assertSplit("f = fn -> 1 end\nf.()", "1.20.0-rc.5", "expanded {f:0} next 1", "expanded {f:1} next 2")

    fun testTheArgumentsOfAnAnonymousCallAreExpanded() =
        assertSplit(
            "f = fn _ -> 1 end\nf.(y)",
            "1.15.0-rc.0",
            "error undefined_function `y`",
            "error undefined_var `y`",
        )

    fun testAnAnonymousCallOfAnAtom() =
        assertSplit(":a.()", "1.18.0-rc.0", "error invalid_function_call `:a.()`", "expanded {} next 0")

    fun testAnAnonymousCallInAPatternIsInvalid() = assertEvery("f.() = 1", "error invalid_pattern_in_match `f.()`")

    fun testAnAnonymousCallInAGuardIsInvalid() =
        assertEvery("case 1 do\nx when x.() -> x\nend", "error invalid_expr_in_guard `x.()`")
}
