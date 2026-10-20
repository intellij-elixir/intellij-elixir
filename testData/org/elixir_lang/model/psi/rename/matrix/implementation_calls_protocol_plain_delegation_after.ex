defprotocol Size do
  def fresh(data)
end

defmodule Counter do
  def fresh(map), do: map_size(map)
end

defimpl Size, for: List do
  def fresh(list), do: length(list)
end

defimpl Size, for: Map do
  @spec fresh(map) :: integer
  @compile inline: [fresh: 1]
  defdelegate fresh(map), to: Counter

  def twice(map), do: fresh(map) * 2
end
