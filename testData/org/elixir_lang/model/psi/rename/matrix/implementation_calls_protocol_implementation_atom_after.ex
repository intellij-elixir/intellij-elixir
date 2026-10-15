defprotocol Size do
  def fresh(data)
end

defimpl Size, for: List do
  def fresh(list), do: length(list)
end

defmodule SizeClient do
  def by_implementation_atom(data), do: apply(Size.List, :fresh, [data])

  def by_protocol(data), do: Size.fresh(data)
end
