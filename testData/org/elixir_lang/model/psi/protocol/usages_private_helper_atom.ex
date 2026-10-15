defprotocol UsagesPrivateHelperAtom do
  def size(data)
end

defimpl UsagesPrivateHelperAtom, for: List do
  def size(list), do: length(list)

  def twice(list), do: si<caret>ze(list) * 2
end

defimpl UsagesPrivateHelperAtom, for: Map do
  def count(map), do: apply(UsagesPrivateHelperAtom.Map, :size, [map])

  defp size(map), do: map_size(map)
end
