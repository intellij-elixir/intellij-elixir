defmodule PhoenixComponentFixtureWeb.Routes.Interp do
  use PhoenixComponentFixtureWeb, :verified_routes
  def f(id), do: ~p"/users/#{id}"
end
