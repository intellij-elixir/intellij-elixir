defmodule PhoenixComponentFixtureWeb.Routes.Quoted do
  use PhoenixComponentFixtureWeb, :verified_routes
  defmacro m, do: quote(do: ~p"/")
  def f, do: 1
end
