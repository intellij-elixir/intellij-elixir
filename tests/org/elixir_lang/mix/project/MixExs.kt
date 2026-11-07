package org.elixir_lang.mix.project

/** A `mix.exs` of one app, `:test_app`. */
internal val MIX_EXS = """
    defmodule TestApp.MixProject do
      use Mix.Project

      def project do
        [app: :test_app, version: "0.1.0"]
      end
    end
""".trimIndent()

/** A `mix.exs` of one app, [app]. */
internal fun mixExs(app: String): String = """
    defmodule App.MixProject do
      use Mix.Project

      def project do
        [app: :$app, version: "0.1.0"]
      end
    end
""".trimIndent()
