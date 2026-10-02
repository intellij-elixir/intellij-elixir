defmodule RouterHelpersQuotedAsSuppressedTest do
  alias IcWeb.Router.Helpers, as: :"Elixir.Routes"

  Routes.device_url(Endpoint, :index, tag_id: 123)
end
