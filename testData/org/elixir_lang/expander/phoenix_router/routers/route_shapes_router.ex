defmodule DemoWeb.RouteShapesRouter do
  use Phoenix.Router, helpers: false

  get "/", DemoWeb.PageController, :home
  get "/users/register", DemoWeb.PageController, :register
  get "/users/:id", DemoWeb.PageController, :show
  get "/users/:id/edit", DemoWeb.PageController, :edit
  put "/users/:id/edit", DemoWeb.PageController, :update
  get "/users/:id/posts/:post_id", DemoWeb.PageController, :post
  get "/assets/css-:md5", DemoWeb.PageController, :css
  get "/files/*path", DemoWeb.PageController, :file
  forward "/mailbox", Plug.Swoosh.MailboxPreview
end
