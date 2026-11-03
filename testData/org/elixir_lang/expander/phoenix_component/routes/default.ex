defmodule PhoenixComponentFixtureWeb.Routes.Default do
  use PhoenixComponentFixtureWeb, :verified_routes
  def f(x \\ ~p"/"), do: x
end
