defmodule ExUnitBodyOrigins.NestedQuoteTest do
  use ExUnit.Case

  defp foo(a, b), do: {a, b}

  for q <- [2] do
    setup context do
      a = 1
      [v: foo(quote(do: unquote(a)), unquote(q)), c: context]
    end
  end
end
