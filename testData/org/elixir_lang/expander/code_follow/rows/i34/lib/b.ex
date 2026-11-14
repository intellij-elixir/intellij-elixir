defmodule B do
  Code.eval_string("Code.ensure_compiled(A)\ndefmodule H do\n  def v, do: :mine\nend")
  @v H.v()
  def v, do: @v
end
