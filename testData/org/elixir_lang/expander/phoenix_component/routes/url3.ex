defmodule PhoenixComponentFixtureWeb.Routes.Url3 do
  use PhoenixComponentFixtureWeb, :verified_routes
  def f(conn), do: url(conn, PhoenixComponentFixtureWeb.Router, ~p"/users")
end
