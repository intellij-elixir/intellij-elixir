defmodule A do
  import B, only: [c: 1], except: []
  @compile {:inline, f: 1}
  @callback c(integer) :: :ok
  def f(x), do: {:a, x}

  def g(x) do
    if x, do: :b, else: :"c d"
  end

  def unquote(:u)(), do: 1
  defdelegate h(y), to: B, as: :c
end
