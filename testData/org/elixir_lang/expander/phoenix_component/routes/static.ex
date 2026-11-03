defmodule PhoenixComponentFixtureWeb.Routes.Static do
  use PhoenixComponentFixtureWeb, :verified_routes
  def f, do: ~p"/images/logo.png"
end
