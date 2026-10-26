defmodule MyApp.Aliased do
  alias Application, as: App

  def aliased, do: App.get_env(:my_app, :aliased)
  def elixir_application, do: Elixir.Application.get_env(:my_app, :elixir_application)
  def erlang, do: :application.get_env(:my_app, :erlang)
  def get_env1, do: App.get_env(:get_env1)
end
