defprotocol Sized do
  def size(data)
end

defimpl Sized, for: Map do
  def size(map), do: fresh(map)

  defp fresh(map) when map_size(map) == 0, do: 0
  defp fresh(map), do: map_size(map)
end

defimpl Sized, for: List do
  def size(list), do: helper(list)

  defp helper(list), do: length(list)
end
