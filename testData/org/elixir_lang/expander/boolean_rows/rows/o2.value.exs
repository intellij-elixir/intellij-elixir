defimpl String.Chars, for: Tuple do
  def to_string(_) do
    Config.config(:app, k: 2)
    "t"
  end
end

config :app, k: 0
config :app, k1: to_string(is_binary(System.get_env("X")))
