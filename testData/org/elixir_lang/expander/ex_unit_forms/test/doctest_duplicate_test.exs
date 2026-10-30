defmodule ExUnitForms.DoctestDuplicateTest do
  use ExUnit.Case

  doctest ExUnitForms.Doc

  test "same" do
    assert true
  end

  test "same" do
    assert true
  end
end
