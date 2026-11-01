defmodule ExUnitBodyOrigins.TwoSplicesTest do
  use ExUnit.Case

  defp baz(v), do: v
  defp bar(v), do: v
  defp foo(a, b, c), do: {baz(a), b, c}

  for {q, ys} <- [{2, [quote(line: 9, do: baz(bar(3))), 9]}] do
    setup context do
      [v: foo(unquote_splicing([]), unquote_splicing(ys), bar(unquote(q))), c: context]
    end
  end
end
