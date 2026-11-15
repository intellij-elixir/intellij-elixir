defmodule A do
  Application.put_env(:p, :key, :other)
  {v, _} = Code.eval_string("Application.get_env(:p, :key)")
  @v v
  def r, do: @v
end
