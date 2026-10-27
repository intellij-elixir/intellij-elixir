defmodule ExUnitForms.RegisterFalseTest do
  use ExUnit.Case, register: false

  test "unregistered" do
    assert true
  end
end
