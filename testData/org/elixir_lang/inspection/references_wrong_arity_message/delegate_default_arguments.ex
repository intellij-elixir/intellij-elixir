defmodule DefaultArgumentsTarget do
  def foo(x, y \\ 0), do: {x, y}
end

defmodule DefaultArgumentsDelegate do
  defdelegate foo(x), to: DefaultArgumentsTarget

  def caller do
    foo(1, 2)
  end
end
