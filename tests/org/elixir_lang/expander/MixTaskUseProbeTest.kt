package org.elixir_lang.expander

/**
 * `use Mix.Task`, whose output gives `Enum.each/2` a capture that registers attributes, and module bodies that do the
 * same compiled on the leg's Elixir.
 */
class MixTaskUseProbeTest : SdkProbeTestCase(accounting = true) {
    /** The leg's node doesn't load Mix, so these aren't compared: [testACaptureGivenToACallRunsWhereItIsCalled] is. */
    fun testUseMixTaskContinues() = assertContinues("use Mix.Task\n\ndef run(_args), do: :ok\ndef after_it, do: :ok")

    fun testUseMixTaskOnOneLineContinues() = assertContinues("use Mix.Task; def run(_), do: :ok; def after_it, do: :ok")

    fun testACaptureGivenToACallRunsWhereItIsCalled() {
        val expansions = listOf(
            "use $TASK\ndef run(_), do: :ok",
            "Enum.each([:a], &Module.register_attribute(__MODULE__, &1, persist: true))\n@a 1\ndef f, do: @a",
            "Enum.each([:a], &Module.register_attribute(__MODULE__, &1, accumulate: true))\n@a 1\n@a 2\ndef f, do: @a",
            "Enum.each([:a], &Module.put_attribute(__MODULE__, &1, 1))\ndef f, do: @a",
            "Enum.each([:a], &Module.put_attribute(__MODULE__, &1, 1))\n@on_load :f\ndef f, do: :ok",
            "Enum.map([:a], &Module.get_attribute(__MODULE__, &1))\ndef f, do: 1",
        ).associateWith { expand(it, PREAMBLE) }

        assertEquals(
            "",
            expansions.filterValues { it.ended !is ExpansionResult.Ended.Compiled }
                .map { (body, expansion) -> "${body.replace("\n", "; ")}: ${expansion.ended}" }
                .joinToString("\n"),
        )
        printed { probes.assertMatchesElixir(expansions) }
    }

    /** A capture is followed as the `fn` it becomes is, where the walk runs it and where it doesn't. */
    fun testACaptureEndsAsItsFnDoes() {
        val ended = { list: String, function: String -> expand("Enum.each($list, $function)\ndef f, do: 1", "").ended::class }

        for (list in listOf("[]", "[:a]", "[:a, :b]")) {
            assertEquals(
                list,
                ended(list, "fn x -> Module.put_attribute(__MODULE__, x, 1) end"),
                ended(list, "&Module.put_attribute(__MODULE__, &1, 1)"),
            )
        }
    }

    /** Only a function call runs what it is given: a capture held in a variable may run any number of times, or none. */
    fun testACaptureNotGivenToACallIsNotFollowed() {
        val bodies = listOf(
            "f = &Module.put_attribute(__MODULE__, &1, 1)\nf.(:a)\n@on_load :g\ndef g, do: :ok",
            "_ = &Module.put_attribute(__MODULE__, &1, 1)\n@on_load :g\ndef g, do: :ok",
            "f = &Module.put_attribute(__MODULE__, &1, 1)\n_ = f\n@on_load :g\ndef g, do: :ok",
            "_ = &is_nil(Module.put_attribute(__MODULE__, &1, 1))\n@on_load :g\ndef g, do: :ok",
            "_ = &(x = Module.put_attribute(__MODULE__, &1, 1))\n@on_load :g\ndef g, do: :ok",
            "f = &is_nil(Module.put_attribute(__MODULE__, &1, 1))\n_ = f\n@on_load :g\ndef g, do: :ok",
            "f = &is_nil(Module.put_attribute(__MODULE__, &1, 1))\nf.(:a)\n@on_load :g\ndef g, do: :ok",
            "f = &is_nil(Module.put_attribute(__MODULE__, &1, 1))\nEnum.each([:a], f)\n@on_load :g\ndef g, do: :ok",
        )

        assertEquals(
            bodies.joinToString("\n") { "$it: Unported" },
            bodies.joinToString("\n") { "$it: ${expand(it, "").outcome::class.simpleName}" },
        )
    }

    private fun assertContinues(body: String) {
        val expansion = expand(body, "")

        assertEquals("${expansion.outcome}", ExpansionResult.Ended.Compiled, expansion.ended)
    }

    private companion object {
        const val TASK = "MixTaskLike{token}"

        val PREAMBLE =
            """
            defmodule $TASK do
              defmacro __using__(_opts) do
                quote do
                  Enum.each(
                    [:shortdoc, :recursive],
                    &Module.register_attribute(__MODULE__, &1, persist: true)
                  )
                end
              end
            end
            """.trimIndent()
    }
}
