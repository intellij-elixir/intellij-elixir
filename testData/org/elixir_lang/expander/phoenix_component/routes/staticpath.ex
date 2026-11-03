defmodule PhoenixComponentFixtureWeb.Routes.Staticpath do
  use PhoenixComponentFixtureWeb, :verified_routes
  def f(conn), do: static_path(conn, "/images/logo.png")
end
