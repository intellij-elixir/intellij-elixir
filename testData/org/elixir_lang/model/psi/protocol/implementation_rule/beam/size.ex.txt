defmodule Sizes do
  def atom_size(atom), do: byte_size(Atom.to_string(atom))
end

defprotocol Size do
  def size(data)
end

defimpl Size, for: List do
  def size(list), do: length(list)
end

defimpl Size, for: Map do
  def count(map), do: size(map)
  defp size(map), do: map_size(map)
end

defimpl Size, for: Tuple do
  defguard size(tuple) when is_tuple(tuple)
end

defimpl Size, for: BitString do
  defmacro size(binary), do: binary
end

defimpl Size, for: Atom do
  defdelegate size(atom), to: Sizes, as: :atom_size
end
