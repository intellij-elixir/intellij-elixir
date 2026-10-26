defmodule MyApp.Worker do
  def request_timeout, do: Application.get_env(:my_app, :request_timeout)
  def refresh, do: Application.fetch_env!(:app_read, :refresh)
  def region(app), do: Application.get_env(app, :region)
  def cache, do: Application.get_env(:my_app, MyApp.Cache)
  def store, do: Application.get_env(:my_app, :"Elixir.MyApp.Store")
  def queue, do: Application.get_env(:my_app, :queue)
end
