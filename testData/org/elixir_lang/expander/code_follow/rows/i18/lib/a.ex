defmodule A do
  Code.ensure_compiled(B)
  @v :from_a
  def r, do: @v
end
