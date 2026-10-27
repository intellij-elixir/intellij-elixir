defmodule ExUnitForms.MixProject do
  use Mix.Project

  def project do
    [app: :ex_unit_forms, version: "0.1.0", elixir: ">= 1.11.0", start_permanent: false, deps: deps()]
  end

  def application, do: [extra_applications: [:ex_unit]]

  defp deps, do: [{:ex_unit_forms_template, path: "deps/ex_unit_forms_template"}]
end
