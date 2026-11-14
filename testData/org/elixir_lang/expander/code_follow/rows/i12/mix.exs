defmodule P.MixProject do
  use Mix.Project
  def project, do: [app: :p, version: "0.1.0", elixirc_paths: if(Mix.env() == :test, do: ["lib", "test/support"], else: ["lib"])]
end
