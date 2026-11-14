x = Code.eval_quoted(quote(do: 2 + 2))
defmodule R do
  @v x
  def r, do: @v
end
