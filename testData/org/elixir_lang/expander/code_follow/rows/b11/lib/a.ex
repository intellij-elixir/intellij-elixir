defmodule A do
  {m, _} = Code.eval_string("__MODULE__")
  @m m
  {e, _} = Code.eval_string("__MODULE__", [], __ENV__)
  @e e
  {d, _} = Code.eval_string("__DIR__", [], file: "/x/y.ex")
  @d d
  {n, _} = Code.eval_string("__ENV__.file")
  @n n
  def r, do: {@m, @e, @d, @n}
end
