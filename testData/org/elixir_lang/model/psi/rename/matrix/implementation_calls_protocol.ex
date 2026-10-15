defprotocol Size do
  def size(data)
  def count(data)
end

defimpl Size, for: List do
  @spec size(list) :: non_neg_integer
  def size(list), do: length(list)

  def count(list), do: size(list)

  def capture, do: &size/1
end

defimpl Size, for: Map do
  def size(map), do: map_size(map)

  def count(map), do: size(map)
end

defmodule SizeClient do
  def by_protocol(data), do: Size.size(data)

  def by_implementation(data), do: Size.List.size(data)
end
