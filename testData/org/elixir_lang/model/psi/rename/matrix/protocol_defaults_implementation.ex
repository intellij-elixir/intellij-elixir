defprotocol Size do
  def size(data, options \\ [])
end

defimpl Size, for: List do
  def size(list, options), do: length(list) + length(options)
end

defmodule Caller do
  import Size, only: [size: 1]

  def one(x), do: size(x)
  def two(x), do: Size.size(x, [])
end
