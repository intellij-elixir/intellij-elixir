defprotocol Size do
  def size(data)
end

defmodule SizeClient do
  def by_atom(data), do: apply(Size, :size, [data])

  def by_protocol(data), do: Size.size(data)
end
