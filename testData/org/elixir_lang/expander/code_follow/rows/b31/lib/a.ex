defmodule A do
  @v elem(Code.eval_string("__ENV__.context_modules", [], __ENV__), 0)
  def r, do: @v
end
