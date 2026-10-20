defmodule M do
  defp f(a), do: a
  def f(a, b), do: {a, b}
end

defmodule Usage do
  def run, do: apply(M, :<caret>f, [1])
end
