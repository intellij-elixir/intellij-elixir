defmodule MyApp.Worker do
  @app :my_app
  @nested Application.compile_env(:my_app, [:greeting, :nested])

  def greeting, do: Application.get_env(:my_app, :greeting)
  def retries, do: Application.fetch_env!(:my_app, :retries)
  def piped, do: :my_app |> Application.get_env(:greeting)
  def level, do: Application.get_env(@app, :level)
  def timeout, do: Application.get_env(:library_app, :timeout)
  def repo, do: Application.get_env(:my_app, MyApp.Repo)
  def nested, do: @nested
  def typo, do: Application.get_env(:my_app, :greting)
end
