defmodule H2 do
  def g(false, _), do: :b
  def g(true, false), do: :a
  def g(true, true), do: :c
end

config :app, k: (H2.g(is_binary(System.get_env("X")), System.get_env("X") == "y")), k1: 1
