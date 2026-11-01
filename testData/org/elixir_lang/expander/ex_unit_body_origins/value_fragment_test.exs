defmodule ExUnitBodyOrigins.ValueFragmentTest do
  use ExUnit.Case

  for n <- [1, 2] do
    setup context do
      y = context[:k]
      [n: unquote(n), y: y, sum: unquote(n) + (y + y)]
    end

    test "t #{n}", %{n: m} do
      z = m
      assert z == unquote(n)
    end
  end
end
