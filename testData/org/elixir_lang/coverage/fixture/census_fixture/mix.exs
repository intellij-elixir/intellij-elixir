defmodule CensusFixture.MixProject do
  use Mix.Project

  def project do
    [app: :census_fixture, version: "0.1.0", elixir: ">= 1.11.0", deps: [{:census_dep, path: "../census_dep"}]]
  end
end
