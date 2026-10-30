defmodule ExUnitForms.ForReadTest do
  use ExUnit.Case

  for x <- [1] do
    test "t #{x}" do
      assert x == 1
    end
  end
end
