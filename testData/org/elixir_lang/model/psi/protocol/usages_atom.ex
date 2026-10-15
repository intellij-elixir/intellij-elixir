defprotocol UsagesAtom do
  def si<caret>ze(data)
end

defimpl UsagesAtom, for: List do
  def size(list), do: length(list)
end

defmodule UsagesAtomClient do
  def by_atom(data), do: apply(UsagesAtom, :size, [data])
end
