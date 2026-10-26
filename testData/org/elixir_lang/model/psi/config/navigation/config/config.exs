import Config

config :my_app, greeting: "hello", retries: 3
config :my_app, MyApp.Repo, pool_size: 10
config :my_app, :"Elixir.MyApp.Worker", size: 1
config :logger, level: :info
config :library_app, timeout: 5

import_config "#{config_env()}.exs"
