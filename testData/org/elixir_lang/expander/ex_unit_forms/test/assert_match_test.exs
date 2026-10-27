defmodule ExUnitForms.AssertMatchTest do
  use ExUnit.Case

  assert {:ok, x} = {:ok, 1}
  def unquote(:"bound_#{x}")(), do: 1

  test "match" do
    assert {:ok, y} = {:ok, 2}
    assert y == 2
  end
end
