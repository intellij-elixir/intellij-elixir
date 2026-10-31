defmodule ExUnitBodyOrigins.EqualAfterSpliceTest do
  use ExUnit.Case

  defp bar(v), do: v
  defp foo(a, b, c), do: {a, b, c}

  for _ <- [1] do
    setup context do
      [v: foo(unquote_splicing([0]), bar(2), bar(2)), c: context]
    end
  end
end
