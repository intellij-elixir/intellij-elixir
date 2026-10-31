defmodule ExUnitBodyOrigins.SpliceArityTest do
  use ExUnit.Case

  defp foo(b), do: b

  for xs <- [[], []] do
    setup context do
      y = context
      [v: foo(unquote_splicing(xs), y)]
    end
  end
end
