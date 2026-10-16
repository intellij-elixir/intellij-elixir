package org.elixir_lang.documentation

/** A delegation with defaults is one function at each arity it declares, so a use at a lower one documents it as at the full one. */
class DelegationWithDefaultsQuickDocumentationTest : QuickDocumentationTestCase() {
    fun testQuickDocAtALowerArityOfADelegationWithDefaultsIsTheFullAritys() {
        for ((module, doc) in listOf("Plain" to "", "Documented" to "@doc \"The delegation's snoc.\"\n  ")) {
            val shown = docsAtEachUse(module, doc)
            val full = shown.getValue("full")

            assertNotNull("$module: nothing at the full arity", full)
            assertEquals(module, shown.mapValues { full }, shown)
        }
    }

    /** Each case its own module: the project keeps the files of the one before. */
    private fun docsAtEachUse(module: String, doc: String): Map<String, String?> {
        myFixture.addFileToProject(
            "target_$module.ex",
            "defmodule $module.Target do\n  def snoc(q, x), do: {q, x}\nend\n"
        )
        myFixture.addFileToProject(
            "delegator_$module.ex",
            "defmodule $module do\n  ${doc}defdelegate snoc(q, x \\\\ nil), to: $module.Target\nend\n"
        )
        val caller = """
            defmodule Caller$module do
              def at_full(a), do: $module.snoc(a, a)
              def at_call(a), do: $module.snoc(a)
              def at_capture(a), do: &$module.snoc/1
            end
        """.trimIndent()

        return mapOf(
            "full" to "$module.sn<caret>oc(a, a)",
            "call" to "$module.sn<caret>oc(a)",
            "capture" to "&$module.sn<caret>oc/1"
        ).mapValues { (_, caretAt) ->
            myFixture.configureByText("caller_$module.ex", caller.replace(caretAt.replace("<caret>", ""), caretAt))

            quickDocumentationAtCaret()
        }
    }
}
