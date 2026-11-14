defmodule A do
  Code.require_file("../support/h.ex", __DIR__)
  defmodule I do
    import H
    def r, do: v()
  end
end
