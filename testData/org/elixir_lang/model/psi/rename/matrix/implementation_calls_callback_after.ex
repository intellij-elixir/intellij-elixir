defmodule Sized do
  @callback fresh(term) :: integer
end

defmodule Impl do
  @behaviour Sized

  @spec fresh(list) :: integer
  @impl Sized
  def fresh(list), do: length(list)

  def count(list), do: fresh(list)

  def capture, do: &fresh/1
end

defmodule ImplClient do
  def by_implementation(data), do: Impl.fresh(data)
end
