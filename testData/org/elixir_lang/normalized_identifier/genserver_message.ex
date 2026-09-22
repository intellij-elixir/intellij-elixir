defmodule NormalizedGenServer do
  use GenServer

  def pop(pid), do: GenServer.call(pid, :sn<caret>oć)

  @impl true
  def handle_call(:snoć, _from, state) do
    {:reply, state, state}
  end
end
