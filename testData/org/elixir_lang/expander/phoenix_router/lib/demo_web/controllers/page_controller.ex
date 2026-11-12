defmodule DemoWeb.PageController do
  use DemoWeb, :controller

  def home(conn, _params) do
    render(conn, :home)
  end
end
