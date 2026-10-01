defmodule A do
  @doc """
  Example:

      iex> f(do: :a, else: {:b, 1})
      :a
  """
  def f(x), do: x
end
