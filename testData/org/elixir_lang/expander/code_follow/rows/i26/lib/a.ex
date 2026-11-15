defmodule A do
  Code.require_file("../support/h.ex", __DIR__)

  defmodule N do
    def v, do: :from_n
  end
end
