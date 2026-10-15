defprotocol Size do
  def size(data)
end

defmodule Counter do
  def size(map), do: map_size(map)
end

defimpl Size, for: List do
  def size(list), do: length(list)
end

defimpl Size, for: Map do
  @spec size(map) :: integer
  @compile inline: [size: 1]
  defdelegate size(map), to: Counter

  def twice(map), do: size(map) * 2
end
