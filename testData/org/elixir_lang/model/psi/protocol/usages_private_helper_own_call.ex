defprotocol UsagesPrivateHelperOwn do
  def size(data)
end

defimpl UsagesPrivateHelperOwn, for: List do
  def size(list), do: length(list)

  def twice(list), do: size(list) * 2
end

defimpl UsagesPrivateHelperOwn, for: Map do
  def count(map), do: si<caret>ze(map)

  defp size(map), do: map_size(map)
end
