defmodule Stops do
  def bar(x), do: x
  def bar_baz(x), do: x
  def bar(x, y), do: {x, y}
  def bar(x) when is_integer(x), do: x
end

defmodule Forward do
  defdelegate size(x), to: Stops, as: :bar
end

defmodule Reader do
  def run, do: Forward.size(1)
end
