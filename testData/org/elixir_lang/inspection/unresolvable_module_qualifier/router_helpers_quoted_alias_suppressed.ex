defmodule RouterHelpersQuotedAliasSuppressedTest do
  alias :"Elixir.IcWeb.Router.Helpers"

  Helpers.device_url(Endpoint, :index, tag_id: 123)
end
