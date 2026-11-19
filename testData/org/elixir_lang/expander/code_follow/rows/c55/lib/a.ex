defmodule A do
  require M
  M.m(1)
  @v Macro.unique_var(:y, A)
  def r, do: @v
end
