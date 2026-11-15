defmodule P.MixProject do
  use Mix.Project
  def project, do: [app: :p, version: "0.1.0", deps: [{:d, path: "deps_src/d"}]]
end
