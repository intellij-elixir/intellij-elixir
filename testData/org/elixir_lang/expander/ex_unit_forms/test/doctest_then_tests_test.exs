defmodule ExUnitForms.DoctestThenTestsTest do
  use ExUnit.Case

  doctest ExUnitForms.Doc

  test "first", context do
    assert context.test
  end

  test "second" do
    assert true
  end

  describe "a group" do
    test "inside one", %{test: user} do
      assert user
    end

    test "inside two" do
      assert true
    end
  end

  test "last" do
    assert true
  end
end
