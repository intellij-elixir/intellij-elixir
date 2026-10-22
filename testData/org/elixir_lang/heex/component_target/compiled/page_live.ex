defmodule MyAppWeb.PageLive do
  import Kernel, only: [abs: 1]

  def local_probe(assigns), do: abs(assigns)
  def remote_probe(assigns), do: Kernel.abs(assigns)
end
