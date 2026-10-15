defmodule Sized do
  @callback fresh(term) :: term
end

defmodule Impl do
  @behaviour Sized

  @impl Sized
  def fresh(x, option \\ nil)
  def fresh(x, :a), do: x
  def fresh(x, option), do: {x, option}

  def once(x), do: fresh(x)
  def twice(x), do: fresh(x, :a)
end
