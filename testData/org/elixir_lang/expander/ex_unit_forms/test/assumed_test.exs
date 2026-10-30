defmodule ExUnitForms.AssumedTest do
  use ExUnit.Case

  if true do
    x = Application.fetch_env!(:ex_unit, :assert_receive_timeout)

    test "a" do
      assert true
    end

    test "b" do
      assert true
    end
  end

  test "c" do
    assert true
  end
end
