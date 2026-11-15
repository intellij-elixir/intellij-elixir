if System.get_env("NOPE"), do: Code.require_file("h.ex", __DIR__)

defmodule R do
  @v is_list(Code.require_file("h.ex", __DIR__))
  def r, do: @v
end
