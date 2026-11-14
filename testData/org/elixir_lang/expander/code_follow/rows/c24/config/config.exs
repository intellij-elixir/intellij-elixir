import Config
Code.eval_string(System.get_env("NOPE") || "config :p, a: :computed", [], __ENV__)
