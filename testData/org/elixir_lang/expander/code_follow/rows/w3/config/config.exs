import Config
if System.get_env("NOPE"), do: Code.require_file("h.ex", __DIR__)
config :p, a: Code.ensure_loaded?(H)
