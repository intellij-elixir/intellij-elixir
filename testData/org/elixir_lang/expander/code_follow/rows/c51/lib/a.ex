defmodule A do
  _ = Enum.map([], fn _ -> defmodule B do Macro.unique_var(:x, A) end end)
  @v Macro.unique_var(:y, A)
  def r, do: @v
end
