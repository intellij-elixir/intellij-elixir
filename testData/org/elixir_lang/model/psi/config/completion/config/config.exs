import Config

config :my_app, retries: 3
config :my_app, :"needs-quotes", true
config :my_app, MyApp.Repo, pool_size: 10
config :my_app, :"Elixir.MyApp.Cache", ttl: 60
config :app_configured, reconnect: true
