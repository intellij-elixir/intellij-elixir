defmodule ExUnitBodyOrigins.EqualOnALineTest do
  use ExUnit.Case

  defp foo(v), do: v

  for n <- [1] do
    setup context do x = context; foo(x); x; unquote(n); :ok end
  end
end
