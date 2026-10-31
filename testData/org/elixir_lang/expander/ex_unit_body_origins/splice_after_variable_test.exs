defmodule ExUnitBodyOrigins.SpliceAfterVariableTest do
  use ExUnit.Case

  defp foo(a, b), do: {a, b}

  for _ <- [1] do
    setup context do
      y = context
      [v: foo(unquote_splicing([]), y, unquote_splicing([1])), c: context]
    end
  end
end
