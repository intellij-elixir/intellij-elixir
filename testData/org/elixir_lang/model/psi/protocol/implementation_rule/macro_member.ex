defprotocol Shape do
  defmacro describe(shape), do: shape
end

defimpl Shape, for: Map do
  defmacro describe(shape), do: shape
end

defimpl Shape, for: List do
  def describe(shape), do: shape
end
