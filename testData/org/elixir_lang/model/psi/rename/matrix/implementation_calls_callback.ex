defmodule Sized do
  @callback size(term) :: integer
end

defmodule Impl do
  @behaviour Sized

  @spec size(list) :: integer
  @impl Sized
  def size(list), do: length(list)

  def count(list), do: size(list)

  def capture, do: &size/1
end

defmodule ImplClient do
  def by_implementation(data), do: Impl.size(data)
end
