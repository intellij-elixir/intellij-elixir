defmodule PhoenixComponentFixtureWeb.Routes.Both do
  use PhoenixComponentFixtureWeb, :verified_routes
  def f(id, b), do: ~p"/users/#{id}?a=#{b}"
end
