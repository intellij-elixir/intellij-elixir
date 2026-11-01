defmodule Phoenix.LiveView.Helpers do
  defmacro live_component(socket, component, assigns \\ [], do_block \\ []) do
    {socket, component, assigns, do_block}
  end
end
