defmodule ExUnitForms.SourceCase do
  use ExUnit.CaseTemplate

  using opts do
    quote do
      import ExUnitForms.SourceCase
      @template_opts unquote(opts)
    end
  end

  setup do
    [from_template: true]
  end

  def helper, do: :ok
end
