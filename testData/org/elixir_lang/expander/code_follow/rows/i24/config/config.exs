import Config
Code.require_file(if(config_env() == :test, do: "../support/t.ex", else: "../support/h.ex"), __DIR__)
:ok
