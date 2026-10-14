defprotocol Size do
  def fresh(data)
end

defimpl Size, for: List do
  def fresh(list, option \\ nil), do: length(list)
  def fresh(list, :a), do: length(list)
end

defimpl Size, for: Map do
  def fresh(map, option \\ nil), do: map_size(map)
  def fresh(map, :a), do: map_size(map)
end
