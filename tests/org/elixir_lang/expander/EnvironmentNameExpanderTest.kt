package org.elixir_lang.expander

/**
 * `__MODULE__`, `__DIR__`, `__CALLER__`, `__ENV__` and `__ENV__.field` in a module body, outside a pattern and in one,
 * at every supported minor and at the first tag of each version difference.
 */
class EnvironmentNameExpanderTest : ExpanderTestCase() {
    override val module: String = "Elixir.Case"

    fun testModuleAndDirExpand() {
        assertEvery("__MODULE__", "expanded {} next 0")
        assertEvery("__DIR__", "expanded {} next 0")
    }

    fun testModuleAndDirInAPattern() {
        assertEvery("__MODULE__ = Case", "expanded {} next 0")
        assertEvery("l = [1]\n[__DIR__ | t] = l", "expanded {l:0 t:1} next 2")
    }

    fun testAnAliasUnderTheModule() = assertEvery("__MODULE__.Foo", "expanded {} next 0")

    fun testCallerIsOnlyAllowedInAMacro() = assertEvery("__CALLER__", "error caller_not_allowed `__CALLER__`")

    fun testCallerInAPattern() =
        assertSplit(
            "__CALLER__ = 1",
            "1.13.0-rc.0",
            "error caller_not_allowed `__CALLER__`",
            "error invalid_pattern_in_match `__CALLER__`",
        )

    fun testEnvExpands() {
        assertEvery("__ENV__", "expanded {} next 0")
        assertEvery("x = 1\n__ENV__", "expanded {x:0} next 1")
    }

    fun testEnvInAPattern() =
        assertSplit(
            "__ENV__ = 1",
            "1.13.0-rc.0",
            "error env_not_allowed `__ENV__`",
            "error invalid_pattern_in_match `__ENV__`",
        )

    fun testAFieldOfTheEnv() {
        for (field in listOf("module", "line", "function", "context", "aliases", "requires", "functions", "macros")) {
            assertEvery("__ENV__.$field", "expanded {} next 0")
        }
        assertEvery("__ENV__.file", "expanded {} next 0")
        assertEvery("__ENV__.__struct__", "expanded {} next 0")
    }

    fun testAFieldTheEnvLacksIsARunTimeCall() = assertEvery("__ENV__.nope", "expanded {} next 0")

    fun testAFieldThatIsNodeState() {
        assertEvery("__ENV__.lexical_tracker", "unported `__ENV__.lexical_tracker`")
        assertEvery("__ENV__.tracers", "unported `__ENV__.tracers`")
    }

    fun testTheVariableFieldsOfTheEnv() {
        assertEvery("__ENV__.versioned_vars", "expanded {} next 0")
        assertSplit(
            "__ENV__.current_vars",
            "1.13.0-rc.0",
            "unported `__ENV__.current_vars`",
            "expanded {} next 0",
        )
    }

    fun testAFieldWithArgumentsIsARunTimeCallOnTheEnv() = assertEvery("__ENV__.line(1)", "expanded {} next 0")

    fun testAFieldOfTheEnvInAPattern() =
        assertSplit(
            "__ENV__.line = 1",
            "1.13.0-rc.0",
            "expanded {} next 0",
            "error invalid_pattern_in_match `__ENV__`",
        )
}
