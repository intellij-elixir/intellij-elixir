defmodule PhoenixComponentFixtureWeb.Router do
  use PhoenixComponentFixtureWeb, :router

  get "/", PhoenixComponentFixtureWeb.PageController, :home
  get "/users", PhoenixComponentFixtureWeb.UserController, :index
  get "/users/:id", PhoenixComponentFixtureWeb.UserController, :show
end
