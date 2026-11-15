defmodule H4 do
  def g(v) when is_boolean(is_map_key(v, :a)), do: :a
  def g(_), do: :b
end

config :app, k: (H4.g(System.get_env("X"))), k1: 1
