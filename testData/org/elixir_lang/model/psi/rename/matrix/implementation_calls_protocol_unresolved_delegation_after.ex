defprotocol Size do
  def fresh(data)
end

defimpl Size, for: List do
  def fresh(list), do: length(list)

  def twice(list), do: fresh(list) * 2
end

defimpl Size, for: Map do
  defdelegate fresh(map), to: Missing, as: :count

  def twice(map), do: fresh(map) * 2
end

defmodule SizeClient do
  def by_protocol(data), do: Size.fresh(data)
end
