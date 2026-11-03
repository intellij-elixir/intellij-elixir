defmodule PhoenixComponentFixtureWeb.Routes.Path2 do
  use PhoenixComponentFixtureWeb, :verified_routes
  def f(conn), do: path(conn, ~p"/users")
end
