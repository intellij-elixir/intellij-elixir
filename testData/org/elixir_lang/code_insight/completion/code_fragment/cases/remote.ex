defmodule Hello.World do
  def wor(x), do: x
  def valid?(x), do: x
  def run!(x), do: x
end

Hello.World.wor(1)
Hello.World.valid?(Hello.World.run!(2))
:erlang.time()
&Hello.World.wor/1
