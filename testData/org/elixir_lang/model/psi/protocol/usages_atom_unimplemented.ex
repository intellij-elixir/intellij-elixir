defprotocol UsagesAtomUnimplemented do
  def si<caret>ze(data)
end

defmodule UsagesAtomUnimplementedClient do
  def by_atom(data), do: apply(UsagesAtomUnimplemented, :size, [data])
end
