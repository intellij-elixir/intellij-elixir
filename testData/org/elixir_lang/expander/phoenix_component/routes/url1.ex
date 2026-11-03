defmodule PhoenixComponentFixtureWeb.Routes.Url1 do
  use PhoenixComponentFixtureWeb, :verified_routes
  def f, do: url(~p"/users")
end
