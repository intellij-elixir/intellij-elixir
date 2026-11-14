defmodule A do
  alias Foo.Bar
  {m, _} = Code.eval_string(~S|elem(Code.eval_string("__MODULE__", [], file: "x"), 0)|, [], __ENV__)
  @m m
  {a, _} = Code.eval_string(~S|elem(Code.eval_string("Bar", [], line: 2), 0)|, [], __ENV__)
  @a a
  def r, do: {@m, @a}
end
