defmodule A do
  defprotocol P do
    def size(data)
  end
end

defmodule B do
  defprotocol P do
    def fresh(data)
  end
end

defimpl A.P, for: List do
  def size(list), do: length(list)
end

defimpl B.P, for: List do
  def fresh(list), do: length(list)
end
