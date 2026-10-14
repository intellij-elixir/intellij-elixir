defprotocol Size do
  def size(data)
end

defimpl Size, for: List do
  def size(list, option \\ nil), do: length(list)
  def size(list, :a), do: length(list)
end

defimpl Size, for: Map do
  def size(map, option \\ nil), do: map_size(map)
  def size(map, :a), do: map_size(map)
end
