defmodule PhoenixComponentFixture.MixProject do
  use Mix.Project

  def project do
    [
      app: :phoenix_component_fixture,
      version: "0.1.0",
      elixir: "~> 1.13",
      lockfile: "locks/#{System.version()}.lock",
      deps: [{:phoenix_live_view, ">= 0.0.0"}]
    ]
  end
end
