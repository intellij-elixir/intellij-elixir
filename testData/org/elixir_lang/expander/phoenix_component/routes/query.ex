defmodule PhoenixComponentFixtureWeb.Routes.Query do
  use PhoenixComponentFixtureWeb, :verified_routes
  def f(params), do: ~p"/users?#{params}"
end
