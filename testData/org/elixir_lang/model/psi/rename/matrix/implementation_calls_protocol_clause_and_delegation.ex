defprotocol Size do
  def size(data)
end

defmodule Counter do
  def count(map), do: Enum.count(map)
end

defimpl Size, for: List do
  def size(list), do: length(list)

  def twice(list), do: size(list) * 2
end

defimpl Size, for: Map do
  defdelegate size(map), to: Counter, as: :count

  def twice(map), do: size(map) * 2
end

defmodule SizeClient do
  def by_protocol(data), do: Size.size(data)
end
