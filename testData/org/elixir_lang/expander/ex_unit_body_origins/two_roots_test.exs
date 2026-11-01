defmodule ExUnitBodyOrigins.TwoRootsMacro do
  defmacro two(a, b) do
    a = Macro.escape(a, unquote: true)
    b = Macro.escape(b, unquote: true)

    quote bind_quoted: [a: a, b: b] do
      def both(x, y), do: (unquote(a); unquote(b))
    end
  end

  def foo(v), do: v
end

defmodule ExUnitBodyOrigins.TwoRoots do
  import ExUnitBodyOrigins.TwoRootsMacro, only: [two: 2, foo: 1]

  for n <- [1] do
    two foo(unquote(n)), foo(unquote(n) + 1)
  end
end
