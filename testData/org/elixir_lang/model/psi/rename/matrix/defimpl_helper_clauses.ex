defprotocol Sized do
  def size(data)
end

defimpl Sized, for: Map do
  def size(map), do: helper(map)

  defp helper(map) when map_size(map) == 0, do: 0
  defp helper(map), do: map_size(map)
end

defimpl Sized, for: List do
  def size(list), do: helper(list)

  defp helper(list), do: length(list)
end
