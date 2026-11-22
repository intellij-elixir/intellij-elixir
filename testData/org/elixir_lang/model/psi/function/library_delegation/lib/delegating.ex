defmodule Delegating do
  defdelegate f(x), to: Delegated
end

defmodule Delegated do
  def f(0), do: 0
  def f(x), do: x
end
