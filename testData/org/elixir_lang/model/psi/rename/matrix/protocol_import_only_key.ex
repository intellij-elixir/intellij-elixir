defprotocol Size do
  def renamee(t)
  def renamee(t, u)
end

defmodule Caller do
  import Size, only: [renamee: 1, renamee: 2]

  def a(x), do: renamee(x)
end
