# One route of each kind `Phoenix.Router.Helpers.define/2` writes helpers for.
defmodule RouterFixture.HelpersRouter do
  use Phoenix.Router

  scope "/", RouterFixture do
    get "/", PageController, :home
    resources "/users", UserController
    get "/files/*path", PageController, :files
    forward "/fwd", Plug.Logger
  end
end
