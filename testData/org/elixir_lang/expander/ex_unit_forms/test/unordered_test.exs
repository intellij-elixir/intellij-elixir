defmodule ExUnitForms.UnorderedTest do
  use ExUnit.Case

  for value <- Process.get(:unordered, []) do
    @other value
  end

  test "first" do
    assert true
  end

  test "second" do
    assert true
  end
end
