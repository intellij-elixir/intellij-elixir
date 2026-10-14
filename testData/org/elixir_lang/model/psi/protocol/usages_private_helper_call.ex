defprotocol UsagesPrivateHelper do
  def size(data)
end

defimpl UsagesPrivateHelper, for: List do
  def size(list), do: length(list)

  def twice(list), do: si<caret>ze(list) * 2
end

defimpl UsagesPrivateHelper, for: Map do
  def count(map), do: size(map)

  defp size(map), do: map_size(map)
end
