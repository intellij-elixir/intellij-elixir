defmodule Target do
  def delegated(a), do: a
end

defmodule Basics do
  @callback both(integer) :: term
  @callback only_callback(integer) :: term

  def both(a), do: a
  def plain(a), do: a
  def two(a, b), do: {a, b}
  defdelegate delegated(a), to: Target
end

defmodule :atom_module do
  def plain(a), do: a
end
