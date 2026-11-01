defmodule ExUnitBodyOrigins.SpliceShiftFragmentTest do
  use ExUnit.Case

  defp bar(v), do: v
  defp foo(a, b, c), do: {a, b, c}

  for {q, ys} <- [{2, [quote(line: 9, do: bar(3)), 9]}] do
    setup context do
      [v: foo(unquote_splicing([]), bar(unquote(q)), unquote_splicing(ys)), c: context]
    end
  end
end
