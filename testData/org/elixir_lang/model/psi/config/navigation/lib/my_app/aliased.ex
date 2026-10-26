defmodule MyApp.Aliased do
  alias Application, as: App

  def greeting, do: App.get_env(:my_app, :greeting)
  def other, do: MyApp.Fake.get_env(:my_app, :greeting)
  def piped, do: :greeting |> :application.get_env()
end
