defmodule PhoenixComponentFixtureWeb.Routes.Attr do
  use PhoenixComponentFixtureWeb, :verified_routes
  @a ~p"/"
  def f, do: @a
end
