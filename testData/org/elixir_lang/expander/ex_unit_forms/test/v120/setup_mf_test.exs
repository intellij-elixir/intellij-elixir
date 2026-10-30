defmodule ExUnitForms.SetupMfTest do
  use ExUnit.Case

  setup {ExUnitForms.Callbacks, :by_mf}

  test "mf", %{mf: mf} do
    assert mf
  end
end
