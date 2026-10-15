defprotocol Size do
  def fresh(data)
end

defmodule Counter do
  def count(list), do: length(list)
end

defimpl Size, for: List do
  @compile inline: [fresh: 1]

  @spec fresh(list) :: integer
  defdelegate fresh(list), to: Counter, as: :count

  def twice(list), do: fresh(list) * 2

  def capture, do: &fresh/1
end

defmodule SizeClient do
  def by_protocol(data), do: Size.fresh(data)
end
