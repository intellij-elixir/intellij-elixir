Code.require_file("support/h.ex")
defmodule A do
  import H
  def r, do: v()
end
