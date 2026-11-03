defmodule PhoenixComponentFixtureWeb.Routes.Path3 do
  use PhoenixComponentFixtureWeb, :verified_routes
  def f(conn), do: path(conn, PhoenixComponentFixtureWeb.Router, ~p"/users")
end
