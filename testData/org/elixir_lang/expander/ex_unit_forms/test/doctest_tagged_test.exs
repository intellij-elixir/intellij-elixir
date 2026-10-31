defmodule ExUnitForms.DoctestTaggedTest do
  use ExUnit.Case

  @tag :slow
  doctest ExUnitForms.Doc

  test "after" do
    assert true
  end
end
