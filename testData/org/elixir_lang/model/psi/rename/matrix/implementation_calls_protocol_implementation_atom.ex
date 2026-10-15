defprotocol Size do
  def size(data)
end

defimpl Size, for: List do
  def size(list), do: length(list)
end

defmodule SizeClient do
  def by_implementation_atom(data), do: apply(Size.List, :size, [data])

  def by_protocol(data), do: Size.size(data)
end
