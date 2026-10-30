defmodule ExUnitForms.DepCase do
  use ExUnit.CaseTemplate

  using opts do
    quote do
      import ExUnitForms.DepCase
      @template_opts unquote(opts)
    end
  end

  setup do
    [from_template: true]
  end

  def helper, do: :ok
end
