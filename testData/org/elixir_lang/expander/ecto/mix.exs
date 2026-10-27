defmodule EctoFixture.MixProject do
  use Mix.Project

  def project do
    [
      app: :ecto_fixture,
      version: "0.1.0",
      elixir: "~> 1.11",
      lockfile: "locks/#{System.version()}.lock",
      deps: [{:ecto_sql, ">= 0.0.0"}, {:postgrex, ">= 0.0.0"}]
    ]
  end
end
