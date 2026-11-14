defmodule B do
  Code.ensure_compiled(A)
  Code.eval_string("defmodule H do def v, do: :from_string end")
  Code.ensure_compiled(A)
  @v H.v()
  def r, do: @v
end
