defmodule Progress.Marked do
  Progress.Macros.send_self(__MODULE__, :erlang.make_ref())
  def f, do: 1
end
