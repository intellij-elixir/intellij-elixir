defmodule PrefixTarget do
  def foo(x), do: x
end

defmodule PrefixDelegate do
  defdelegate foo(x), to: PrefixTarget

  def caller, do: f<caret>o(1)
end
