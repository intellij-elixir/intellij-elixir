defprotocol Size do
  def fresh(data)

  def size(data, options)
end

defmodule SizeClient do
  def by_atom(data), do: apply(Size, :fresh, [data])

  def by_atom(data, options), do: apply(Size, :size, [data, options])
end
