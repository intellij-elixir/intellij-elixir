defprotocol Size do
  def fresh(data)
end

defmodule SizeClient do
  def by_atom(data), do: apply(Size, :fresh, [data])

  def by_protocol(data), do: Size.fresh(data)
end
