defmodule B do
  Code.ensure_compiled(A)
  @v {A.N.v(), H.v()}
  def r, do: @v
end
