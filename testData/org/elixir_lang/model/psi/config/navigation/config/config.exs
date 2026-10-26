import Config

config :my_app, greeting: "hello", retries: 3
config :my_app, MyApp.Repo, pool_size: 10
config :my_app, :"Elixir.MyApp.Worker", size: 1
config :logger, level: :info
config :library_app, timeout: 5
:my_app |> config(:piped, 1)
config(:my_app, ([parenthesised: 2]))

import_config "#{config_env()}.exs"
