defmodule MyAppWeb.CoreComponents do
  args = [:assigns]

  defdelegate button(unquote_splicing(args)), to: MyAppWeb.Components
end
