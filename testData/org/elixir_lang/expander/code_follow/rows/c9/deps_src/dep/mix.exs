defmodule Dep.MixProject do
  use Mix.Project
  def project, do: [app: :dep, version: "0.1.0"]
  def application, do: [env: [key: :value]]
end
