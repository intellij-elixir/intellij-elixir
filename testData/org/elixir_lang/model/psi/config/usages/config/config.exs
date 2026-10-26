import Config

config :my_app, retries: 3
config :my_app, MyApp.Repo, pool_size: 10
config :logger, level: :info
config :library_app, timeout: 5
