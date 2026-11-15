import Config
Code.eval_string("Config.config(:p, a: :computed)", [], line: String.to_integer(System.get_env("NOPE", "1")))
