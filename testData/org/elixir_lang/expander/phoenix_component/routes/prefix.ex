defmodule PhoenixComponentFixtureWeb.Routes.Prefix do
  use Phoenix.VerifiedRoutes, endpoint: PhoenixComponentFixtureWeb.Endpoint, router: PhoenixComponentFixtureWeb.Router, path_prefixes: [{Atom, :to_string, [:en]}]
  def f(id), do: ~p"/users/#{id}"
end
