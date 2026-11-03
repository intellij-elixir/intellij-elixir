defmodule PhoenixComponentFixtureWeb.Routes.Direct do
  use Phoenix.VerifiedRoutes, endpoint: PhoenixComponentFixtureWeb.Endpoint, router: PhoenixComponentFixtureWeb.Router
  def f, do: ~p"/users"
end
