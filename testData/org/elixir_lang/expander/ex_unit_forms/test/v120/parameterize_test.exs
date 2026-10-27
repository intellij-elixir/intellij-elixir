defmodule ExUnitForms.ParameterizeTest do
  use ExUnit.Case, async: true, parameterize: [%{a: 1}]

  test "parameterized", %{a: a} do
    assert a == 1
  end
end
