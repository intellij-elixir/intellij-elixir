defmodule ExUnitForms.ForTest do
  use ExUnit.Case

  for n <- [1, 2] do
    test "number #{n}" do
      assert unquote(n) > 0
    end
  end

  for n <- [3] do
    @context n
    test "attribute #{n}" do
      assert @context == 3
    end
  end
end
