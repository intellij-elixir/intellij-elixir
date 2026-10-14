defprotocol Size do
  def size(data)
end

defmodule Counter do
  def count(list), do: length(list)
end

defimpl Size, for: List do
  @compile inline: [size: 1]

  @spec size(list) :: integer
  defdelegate size(list), to: Counter, as: :count

  def twice(list), do: size(list) * 2

  def capture, do: &size/1
end

defmodule SizeClient do
  def by_protocol(data), do: Size.size(data)
end
