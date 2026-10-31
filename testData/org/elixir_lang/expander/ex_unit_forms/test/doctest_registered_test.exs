defmodule ExUnitForms.DoctestRegisteredTest do
  use ExUnit.Case

  ExUnit.Case.register_attribute(__MODULE__, :registered)
  doctest ExUnitForms.Doc

  test "after" do
    assert true
  end
end
