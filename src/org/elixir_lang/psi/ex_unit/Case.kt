package org.elixir_lang.psi.ex_unit

import com.intellij.psi.ResolveState
import org.elixir_lang.psi.ModuleWalker
import org.elixir_lang.psi.NameArityRangeWalker
import org.elixir_lang.psi.call.Call

object Describe: NameArityRangeWalker("describe", 1..2)
object Test: NameArityRangeWalker("test", 1..3)

object Case: ModuleWalker("ExUnit.Case", Describe, Test) {
    fun isDescribe(call: Call, state: ResolveState): Boolean =
            Describe.hasNameArity(call) && matches(call, state)

    fun isTest(call: Call, state: ResolveState): Boolean =
            Test.hasNameArity(call) && matches(call, state)
}
