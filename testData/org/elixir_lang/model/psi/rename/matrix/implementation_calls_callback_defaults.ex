defmodule Sized do
  @callback run(term) :: term
end

defmodule Impl do
  @behaviour Sized

  @impl Sized
  def run(x, option \\ nil)
  def run(x, :a), do: x
  def run(x, option), do: {x, option}

  def once(x), do: run(x)
  def twice(x), do: run(x, :a)
end
