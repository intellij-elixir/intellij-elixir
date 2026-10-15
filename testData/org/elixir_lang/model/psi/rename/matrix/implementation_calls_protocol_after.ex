defprotocol Size do
  def fresh(data)
  def count(data)
end

defimpl Size, for: List do
  @spec fresh(list) :: non_neg_integer
  def fresh(list), do: length(list)

  def count(list), do: fresh(list)

  def capture, do: &fresh/1
end

defimpl Size, for: Map do
  def fresh(map), do: map_size(map)

  def count(map), do: fresh(map)
end

defmodule SizeClient do
  def by_protocol(data), do: Size.fresh(data)

  def by_implementation(data), do: Size.List.fresh(data)
end
