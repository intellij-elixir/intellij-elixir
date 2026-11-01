defmodule ExUnitBodyOrigins.SharedVariable do
  defmacro both(left, right, body) do
    left = Macro.escape(left, unquote: true)
    right = Macro.escape(right, unquote: true)
    body = Macro.escape(body, unquote: true)

    quote bind_quoted: [left: left, right: right, body: body] do
      def first(unquote(left), _other), do: unquote(body)
      def second(_other, unquote(right)), do: unquote(body)
    end
  end

  defmacro walked(left, right, body) do
    body = Macro.prewalk(body, fn node -> node end)

    quote do
      def walked_first(unquote(left), _other), do: unquote(body)
      def walked_second(_other, unquote(right)), do: unquote(body)
    end
  end

  defmacro given(left, right, body) do
    quote do
      def given_first(unquote(left), _other), do: unquote(body)
      def given_second(_other, unquote(right)), do: unquote(body)
    end
  end

  defmacro twice(pattern, rebound, body) do
    pattern = Macro.escape(pattern, unquote: true)
    rebound = Macro.escape(rebound, unquote: true)
    body = Macro.escape(body, unquote: true)

    quote bind_quoted: [pattern: pattern, rebound: rebound, body: body] do
      def twice(unquote(pattern)) do
        before = unquote(body)
        unquote(rebound) = :after_rebinding
        [before, unquote(body)]
      end
    end
  end
end

defmodule ExUnitBodyOrigins.SharedVariableUser do
  require ExUnitBodyOrigins.SharedVariable

  ExUnitBodyOrigins.SharedVariable.both(
    x,
    x,
    x
  )

  ExUnitBodyOrigins.SharedVariable.walked(
    w,
    w,
    w
  )

  ExUnitBodyOrigins.SharedVariable.given(
    g,
    g,
    g
  )

  ExUnitBodyOrigins.SharedVariable.twice(
    y,
    y,
    y
  )
end

defmodule ExUnitBodyOrigins.SharedVariableReader do
  alias ExUnitBodyOrigins.SharedVariableUser, as: User

  def unquote(User.first(:first_left, :first_right))(var), do: var
  def unquote(User.second(:second_left, :second_right))(var), do: var
  def unquote(User.walked_first(:walked_first_left, :walked_first_right))(var), do: var
  def unquote(User.walked_second(:walked_second_left, :walked_second_right))(var), do: var
  def unquote(User.given_first(:given_first_left, :given_first_right))(var), do: var
  def unquote(User.given_second(:given_second_left, :given_second_right))(var), do: var
  def unquote(hd(User.twice(:before_rebinding)))(var), do: var
  def unquote(hd(tl(User.twice(:before_rebinding))))(var), do: var
end
