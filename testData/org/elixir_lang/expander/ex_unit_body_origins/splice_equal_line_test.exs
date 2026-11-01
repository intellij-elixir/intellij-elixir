defmodule ExUnitBodyOrigins.SpliceEqualLineTest do
  use ExUnit.Case

  defp bar(v), do: v
  defp foo(a, b, c), do: {a, b, c}

  for ys <- [[quote(line: 9, do: bar(2)), 9]] do
    setup context do
      [v: foo(unquote_splicing(ys), bar(2)), c: context]
    end
  end
end
