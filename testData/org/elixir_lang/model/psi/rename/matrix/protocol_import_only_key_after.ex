defprotocol Size do
  def fresh(t)
  def renamee(t, u)
end

defmodule Caller do
  import Size, only: [fresh: 1, renamee: 2]

  def a(x), do: fresh(x)
end
