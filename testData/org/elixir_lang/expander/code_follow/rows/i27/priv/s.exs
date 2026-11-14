defmodule S do
  Code.ensure_compiled(A)
  @v H.v()
  def r, do: @v
end

IO.inspect(S.r(), label: "ANSWER")
