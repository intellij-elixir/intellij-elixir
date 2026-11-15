defmodule A do
  r0 = make_ref()
  {r1, _} = Code.eval_string("make_ref()")
  {r2, _} = Code.eval_string("make_ref()")
  @v {r1 == r2, r0 == r1}
  def r, do: @v
end
