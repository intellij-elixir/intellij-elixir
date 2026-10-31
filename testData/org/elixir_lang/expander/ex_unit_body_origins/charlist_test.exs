defmodule ExUnitBodyOrigins.CharlistTest do
  use ExUnit.Case

  for n <- [1] do
    setup context do
      c = 'abc'
      [c: c, n: unquote(n), context: context]
    end

    test "charlist #{n}", %{c: d} do
      e = 'abc'
      assert d == e
    end
  end
end
