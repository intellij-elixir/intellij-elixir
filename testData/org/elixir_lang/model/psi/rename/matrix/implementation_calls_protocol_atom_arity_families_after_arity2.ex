defprotocol Size do
  def size(data)

  def fresh(data, options)
end

defmodule SizeClient do
  def by_atom(data), do: apply(Size, :size, [data])

  def by_atom(data, options), do: apply(Size, :fresh, [data, options])
end
