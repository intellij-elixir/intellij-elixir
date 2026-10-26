defmodule MyApp.Worker do
  alias MyApp.Repo

  def retries, do: Application.fetch_env!(:my_app, :retries)
  def repo, do: Application.get_env(:my_app, Repo)
  def level(app), do: Application.get_env(app, :level)
  def all, do: Application.get_all_env(:my_app)
  def other, do: Application.get_env(:other_app, :retries)
  def any(key), do: Application.get_env(:my_app, key)
end
