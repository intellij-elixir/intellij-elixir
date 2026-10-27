defmodule ExUnitForms.RegisterAttributeTest do
  use ExUnit.Case

  ExUnit.Case.register_attribute(__MODULE__, :fixture)

  @fixture :x
  test "with fixture", context do
    assert context.registered.fixture == :x
  end
end
