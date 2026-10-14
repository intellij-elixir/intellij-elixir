# The `.beam` beside this is `elixirc clause_reasons.ex -o .` of it.
defmodule ClauseReasons do
  @deprecated "b"
  def old(1), do: 1
  @deprecated "a"
  def old(2), do: 2
end
