defmodule ExUnitBodyOrigins.SpliceArityLineTest do
  use ExUnit.Case

  defp bar(v), do: v
  defp foo(a, b, c), do: {a, b, c}

  for {xs, q} <- [{[1, quote(line: 9, do: bar(3))], 2}] do
    setup context do
      [v: foo(unquote_splicing(xs), bar(unquote(q))), c: context]
    end
  end
end
