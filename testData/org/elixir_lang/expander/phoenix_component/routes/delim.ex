defmodule PhoenixComponentFixtureWeb.Routes.Delim do
  use PhoenixComponentFixtureWeb, :verified_routes
  def f, do: ~p(/x)
end
