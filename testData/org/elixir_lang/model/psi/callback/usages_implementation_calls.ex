defmodule UsagesImplementationCallsBehaviour do
  @callback si<caret>ze(term) :: integer
end

defmodule UsagesImplementationCallsImpl do
  @behaviour UsagesImplementationCallsBehaviour

  @spec size(list) :: integer
  @impl true
  def size(list), do: length(list)

  def twice(list), do: size(list) * 2

  def capture, do: &size/1
end

defmodule UsagesImplementationCallsClient do
  def by_implementation(data), do: UsagesImplementationCallsImpl.size(data)
end
