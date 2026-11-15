defmodule B do
  Code.ensure_compiled(A)
  def x, do: :from_b
end
