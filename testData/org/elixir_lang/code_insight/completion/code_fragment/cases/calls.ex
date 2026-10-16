defmodule C do
  def local(x), do: x
  def f(fun, map) do
    local(1)
    local 2
    C.local(3)
    C.local 4
    fun.(5)
    @attr.(6)
    map.key
    map.key.nested
    map.key.(7)
    local(1).field
    (map).key
    Kernel.+(1, 2)
    if map do 1 else 2 end
  end
end
