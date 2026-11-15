defmodule A do
  {v, _} = Code.eval_string("1 + 1")
  @v v
  {w, b} = Code.eval_string("x = y + 1; x", y: 1)
  @w {w, Enum.sort(b)}
  {q, _} = Code.eval_quoted(quote(do: 2 + 2))
  @q q
  def r, do: {@v, @w, @q}
end
