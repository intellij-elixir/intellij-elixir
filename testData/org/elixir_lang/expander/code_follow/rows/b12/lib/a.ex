defmodule A do
  Code.eval_string("defmodule S do\n def v, do: :from_s\nend")
  @v S.v()
  def r, do: @v
end
