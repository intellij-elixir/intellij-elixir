defmodule H do
  def g(false), do: :b
  def g(true), do: :a
end

config :app, k: (H.g(is_binary(System.get_env("X")))), k1: 1
