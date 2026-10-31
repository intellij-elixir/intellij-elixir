defmodule ExUnitBodyOrigins.PrefixBeforeSpliceTest do
  use ExUnit.Case

  defp baz, do: 1
  defp bar(v), do: v
  defp foo(a, b), do: {a, b}

  for {q, xs} <- [{quote(do: baz()), [3]}] do
    setup context do
      [v: foo(bar(unquote(q)), unquote_splicing(xs)), c: context]
    end
  end
end
