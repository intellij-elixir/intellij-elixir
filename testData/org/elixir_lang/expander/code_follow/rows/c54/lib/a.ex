defmodule A do
  (fn -> if System.get_env("X"), do: Macro.expand(quote(do: unless(true, do: 1)), __ENV__) end).()
  @v Macro.unique_var(:y, A)
  def r, do: @v
end
