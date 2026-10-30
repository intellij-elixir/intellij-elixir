defmodule ExUnitForms.DeclinedTest do
  use ExUnit.Case

  _ = Process.get(:declined)

  test "first" do
    assert true
  end

  test "second" do
    assert true
  end
end
