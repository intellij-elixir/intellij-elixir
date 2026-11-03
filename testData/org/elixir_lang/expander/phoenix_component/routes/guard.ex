defmodule PhoenixComponentFixtureWeb.Routes.Guard do
  use PhoenixComponentFixtureWeb, :verified_routes
  def f(x) when x == ~p"/", do: x
end
