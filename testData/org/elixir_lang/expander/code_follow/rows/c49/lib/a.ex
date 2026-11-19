defmodule A do
  {f, _} = Code.eval_file("../support/w.exs", __DIR__)
  _ = Enum.map([], fn _ -> defmodule B do f.() end end)
  @v Application.get_env(:p, :k)
  def r, do: @v
end
