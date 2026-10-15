defprotocol Size do
  def size(data)
end

defimpl Size, for: List do
  def size(list), do: length(list)
end

defmodule SizeClient do
  def by_atom(data), do: apply(Size, :size, [data])
end
