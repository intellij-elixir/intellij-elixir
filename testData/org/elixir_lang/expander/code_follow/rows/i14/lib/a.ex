defmodule A do
  a = try do
    Code.require_file("../support/boom.ex", __DIR__)
  rescue
    RuntimeError -> :raised
  end
  b = try do
    Code.require_file("../support/boom.ex", __DIR__)
  rescue
    RuntimeError -> :raised
  end
  @v {a, b}
  def r, do: @v
end
