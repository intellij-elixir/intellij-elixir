defprotocol Size do
  def fresh(data, options \\ [])
end

defimpl Size, for: List do
  def fresh(list, options), do: length(list) + length(options)
end

defmodule Caller do
  import Size, only: [fresh: 1]

  def one(x), do: fresh(x)
  def two(x), do: Size.fresh(x, [])
end
