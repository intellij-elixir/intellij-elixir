defmodule PhoenixComponentFixtureWeb.Routes.Noslash do
  use PhoenixComponentFixtureWeb, :verified_routes
  def f, do: ~p"users"
end
