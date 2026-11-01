defmodule ExUnitBodyOrigins.ReversedResultMacro do
  defmacro rev({:foo, m, args}), do: {:foo, m, Enum.reverse(args)}

  def foo(a, b), do: {a, b}
  def bar(v), do: v
end

defmodule ExUnitBodyOrigins.ReversedResultTest do
  use ExUnit.Case
  import ExUnitBodyOrigins.ReversedResultMacro, only: [rev: 1, foo: 2, bar: 1]

  for {x, y} <- [{1, 2}] do
    setup context do
      [v: rev(foo(bar(unquote(x)), bar(unquote(y)))), c: context]
    end
  end
end
