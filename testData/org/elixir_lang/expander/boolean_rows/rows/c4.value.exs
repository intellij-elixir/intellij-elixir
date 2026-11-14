defmodule H3 do
  def g(false, _), do: :b
  def g(true, false), do: :a
end

config :app, k: (H3.g(is_binary(System.get_env("X")), System.get_env("X") == "y")), k1: 1
