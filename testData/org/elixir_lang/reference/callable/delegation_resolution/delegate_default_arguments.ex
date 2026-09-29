defmodule DefaultTarget do
  def foo(x, y \\ 0), do: {x, y}
end

defmodule DefaultDelegate do
  defdelegate foo(x), to: DefaultTarget

  def caller, do: f<caret>oo(1, 2)
end
