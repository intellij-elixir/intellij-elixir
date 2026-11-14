import Config
config :p, a: :earlier
Code.require_file("../lib/h.ex", __DIR__)
config :dep, other_key: H.v()
