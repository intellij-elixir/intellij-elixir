defmodule P.MixProject do
  use Mix.Project
  def project, do: [app: :p, version: "0.1.0", deps: [{:dep, path: "deps_src/dep"}]]
  def application, do: []
end
