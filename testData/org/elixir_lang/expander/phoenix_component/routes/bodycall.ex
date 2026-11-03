defmodule PhoenixComponentFixtureWeb.Routes.Bodycall do
  use PhoenixComponentFixtureWeb, :verified_routes
  IO.inspect(~p"/")
  def f, do: 1
end
