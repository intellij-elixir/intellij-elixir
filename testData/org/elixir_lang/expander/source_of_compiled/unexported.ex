# The `.beam` file beside this is `elixirc unexported.ex -o .` of it.
defmodule SourceOfCompiled.Unexported do
  args = [1, 2]
  def spliced(unquote_splicing(args)), do: :ok

  if false do
    def f(a), do: a
    defp h(a), do: a
    def g(a, b \\ 1), do: {a, b}
  end

  defmacro f(a), do: a

  def h(a), do: a

  def g(a, b), do: {a, b}
end
