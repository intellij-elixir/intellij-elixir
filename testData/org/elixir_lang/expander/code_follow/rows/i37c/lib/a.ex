defmodule A do
  Application.put_env(:dep, :key, :other)
  {v, _} = Code.eval_string("Application.get_env(:dep, :key)", [], __ENV__)
  @v v
  def r, do: @v
end
