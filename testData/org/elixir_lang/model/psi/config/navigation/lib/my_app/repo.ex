defmodule MyApp.Repo do
  def config, do: Application.get_env(:my_app, __MODULE__)
end
