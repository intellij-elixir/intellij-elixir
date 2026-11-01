defmodule ExUnitBodyOrigins.ValueLiteralTest do
  use ExUnit.Case

  for n <- [1, 2] do
    setup context do
      y = context[:k]
      [n: 1, y: y, sum: 1 + (y + y)]
    end

    test "t #{n}", %{n: m} do
      z = m
      assert z == 1
    end
  end
end
