defmodule PhoenixComponentFixtureWeb.Routes.Url2 do
  use PhoenixComponentFixtureWeb, :verified_routes
  def f(conn), do: url(conn, ~p"/users")
end
