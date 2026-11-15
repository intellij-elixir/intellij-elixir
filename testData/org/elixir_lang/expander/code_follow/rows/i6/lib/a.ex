defmodule A do
  {l, _} = Code.eval_string("\n__ENV__.line", [], line: 40)
  {f, _} = Code.eval_string("__ENV__.file", [], file: "/x/y.ex")
  @v {l, f}
  def r, do: @v
end
