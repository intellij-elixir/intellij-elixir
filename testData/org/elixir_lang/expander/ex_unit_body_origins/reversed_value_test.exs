defmodule ExUnitBodyOrigins.ReversedValueMacro do
  defmacro rev({:foo, m, args}) do
    body = Macro.escape({:foo, m, Enum.reverse(args)}, unquote: true)

    quote bind_quoted: [body: body] do
      def r(), do: unquote(body)
    end
  end

  def foo(a, b), do: {a, b}
  def bar(v), do: v
end

defmodule ExUnitBodyOrigins.ReversedValue do
  import ExUnitBodyOrigins.ReversedValueMacro, only: [rev: 1, foo: 2, bar: 1]

  for {x, y} <- [{1, 2}] do
    rev foo(bar(unquote(x)), bar(unquote(y)))
  end
end
