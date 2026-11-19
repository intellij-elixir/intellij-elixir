Application.put_env(:p, :k, :from_required)
Application.put_env(:p, :seen, Application.get_env(:p, :k))
