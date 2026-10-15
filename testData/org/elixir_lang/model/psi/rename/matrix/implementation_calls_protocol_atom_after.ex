defprotocol Size do
  def fresh(data)
end

defimpl Size, for: List do
  def fresh(list), do: length(list)
end

defmodule SizeClient do
  def by_atom(data), do: apply(Size, :fresh, [data])
end
