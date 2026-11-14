defmodule TTest do
  @w H.v()
  def r, do: @w
end

{{p, q}, _} = Code.eval_string(System.get_env("LIB"))
IO.inspect({p, TTest.r(), q}, label: "ANSWER")
