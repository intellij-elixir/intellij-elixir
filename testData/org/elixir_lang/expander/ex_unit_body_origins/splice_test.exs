defmodule ExUnitBodyOrigins.SpliceTest do
  use ExUnit.Case

  defp foo(a, b), do: {a, b}

  for xs <- [[1], [2]] do
    setup context do
      y = context
      [v: foo(unquote_splicing(xs), y)]
    end
  end
end
