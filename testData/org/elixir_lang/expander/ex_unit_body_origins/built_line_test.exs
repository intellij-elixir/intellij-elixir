defmodule ExUnitBodyOrigins.BuiltLine do
  defmacro built, do: {:__block__, [], [quote(line: 9, do: bar(3)), 0]}
end

defmodule ExUnitBodyOrigins.BuiltLineTest do
  use ExUnit.Case
  import ExUnitBodyOrigins.BuiltLine
  defp bar(v), do: v
  for q <- [2], do: setup(context, do: [v: bar(unquote(q)), w: built(), c: context])
end
