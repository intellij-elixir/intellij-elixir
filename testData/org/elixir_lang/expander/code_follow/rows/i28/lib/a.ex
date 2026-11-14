defmodule A do
  Code.require_file("../support/h.ex", __DIR__)
  def unquote(String.to_atom(System.get_env("UNSET", "f")))(), do: 1
end
