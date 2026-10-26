import Config

config :my_app, retries: 3
config :my_app, MyApp.Repo, pool_size: 10
config :logger, level: :info
config :library_app, timeout: 5
config :my_app, aliased: 1
config :my_app, aliased_away: 1
config :my_app, elixir_application: 1
config :my_app, erlang: 1
config :my_app, get_env1: 1
config :my_app, imported: 1
config :my_app, imported_compile_env: 1
config :my_app, local: 1
config :my_app, attribute: 1
config :my_app, unreached_alias: 1
config :my_app, unreached_written: 1
config :my_app, unreached_absolute: 1
config :my_app, parenthesised_held: 1
config :my_app, piped_held: 1
config :my_app, defined_head: 1
config :my_app, guarded_head: 1
