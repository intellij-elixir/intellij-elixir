defmodule ExUnitFormsTemplate.MixProject do
  use Mix.Project

  def project, do: [app: :ex_unit_forms_template, version: "0.1.0", elixir: ">= 1.11.0", deps: []]

  def application, do: [extra_applications: [:ex_unit]]
end
