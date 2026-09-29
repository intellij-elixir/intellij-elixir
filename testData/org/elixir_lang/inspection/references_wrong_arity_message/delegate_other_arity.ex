defmodule DelegateTarget do
  def foo(a), do: a
  def foo(a, b, c), do: {a, b, c}
end

defmodule DelegateOtherArity do
  defdelegate foo(a), to: DelegateTarget

  def caller do
    foo(1, 2)
  end
end
