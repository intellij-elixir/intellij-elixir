defmodule MyApp.Attribute do
  @m Application
  @erlang :application

  def attribute, do: @m.get_env(:my_app, :attribute)
  def parenthesised, do: (@m).get_env(:my_app, :parenthesised_held)
  def piped, do: :piped_held |> @erlang.get_env()
end
