defprotocol Size do
  def size(data)
end

defimpl Size, for: List do
  def size(list), do: twice(list)
  def twice(list), do: length(list) * 2
end

defimpl Size, for: Map do
  def size(map), do: twice(map)
  def twice(map), do: map_size(map) * 2
end
