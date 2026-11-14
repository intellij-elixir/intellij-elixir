defmodule First do
  def x, do: :from_first
end

defmodule A do
  Code.ensure_compiled(First)
  @v First.x()
  def r, do: @v
end
