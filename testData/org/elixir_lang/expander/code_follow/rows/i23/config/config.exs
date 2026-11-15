import Config

defmodule C do
  def v, do: :from_c
end

Code.require_file("../support/h.ex", __DIR__)
:ok
