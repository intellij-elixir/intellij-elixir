defmodule DefaultArgs do
  def renamee(q, x \\ nil), do: {q, x}
end

defmodule Caller do
  import DefaultArgs, only: [renamee: 1, renamee: 2]

  def a(q), do: DefaultArgs.renamee(q, 1)
  def b, do: &DefaultArgs.renamee/1
  def c, do: &DefaultArgs.renamee/2
end
