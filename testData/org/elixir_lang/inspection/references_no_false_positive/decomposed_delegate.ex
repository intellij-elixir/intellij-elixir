defmodule DecomposedDelegateTarget do
  defdelegate snoć(a, b), to: Kernel, as: :length

  def caller(a, b), do: snoć(a, b)
end
