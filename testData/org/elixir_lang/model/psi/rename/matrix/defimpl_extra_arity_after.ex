defprotocol Size do
  def size(data)
end

defimpl Size, for: List do
  def size(list), do: length(list)
  def fresh(list, :b), do: length(list)
end

defimpl Size, for: Map do
  def size(map), do: map_size(map)
  def size(map, :b), do: map_size(map)
end
