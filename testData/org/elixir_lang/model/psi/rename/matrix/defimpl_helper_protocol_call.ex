defprotocol Size do
  def size(data)
end

defimpl Size, for: List do
  def size(list), do: length(list)
end

defimpl Size, for: Tuple do
  def count(tuple), do: size(tuple)

  defp size(tuple), do: elem(tuple, 0)
end

defmodule SizeClient do
  def by_protocol(data), do: Size.size(data)
end
