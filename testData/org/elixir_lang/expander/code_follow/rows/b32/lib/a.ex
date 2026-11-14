defmodule B do
  def f, do: elem(Code.eval_string("__ENV__.function", [], __ENV__), 0)
end

defmodule A do
  @v B.f()
  def r, do: @v
end
