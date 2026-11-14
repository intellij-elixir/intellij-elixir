defmodule B do
  Code.eval_string("Code.ensure_compiled(A)")
  @v H.v()
  def r, do: @v
end
