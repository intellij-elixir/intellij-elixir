defmodule DefaultArgs do
  def fresh(q, x \\ nil), do: {q, x}
end

defmodule Caller do
  import DefaultArgs, only: [fresh: 1, fresh: 2]

  def a(q), do: DefaultArgs.fresh(q, 1)
  def b, do: &DefaultArgs.fresh/1
  def c, do: &DefaultArgs.fresh/2
end
