defmodule ExUnitBodyOrigins.ExpandedArgument do
  defmacro made, do: quote(do: baz(1))

  defmacro escaped(a) do
    body = Macro.escape(a, unquote: true)

    quote bind_quoted: [body: body] do
      def passed(), do: unquote(body)
    end
  end

  defmacro expanded(a) do
    body = Macro.escape(Macro.expand(a, __CALLER__), unquote: true)

    quote bind_quoted: [body: body] do
      def direct(), do: unquote(body)
    end
  end

  defmacro passing(a) do
    expanded = Macro.expand(a, __CALLER__)

    quote do
      ExUnitBodyOrigins.ExpandedArgument.escaped(unquote(expanded))
    end
  end

  defmacro spine(a) do
    body = Macro.escape(a, unquote: true)

    quote bind_quoted: [body: body] do
      def spined(), do: unquote(body)
    end
  end

  defmacro subst(call) do
    Macro.prewalk(call, fn
      {:unquote, _, [_]} -> quote(do: baz(1))
      node -> node
    end)
  end

  def baz(v), do: v
end

defmodule ExUnitBodyOrigins.ExpandedArgumentUser do
  require ExUnitBodyOrigins.ExpandedArgument
  import ExUnitBodyOrigins.ExpandedArgument, only: [made: 0, expanded: 1, passing: 1, spine: 1, subst: 1, baz: 1]

  expanded(made())
  passing(made())
  subst(spine(unquote(1)))
end
