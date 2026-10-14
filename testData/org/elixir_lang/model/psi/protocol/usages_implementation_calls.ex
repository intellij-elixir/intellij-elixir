defprotocol UsagesImplementationCalls do
  def si<caret>ze(data)
end

defimpl UsagesImplementationCalls, for: List do
  @spec size(list) :: integer
  def size(list), do: length(list)

  def twice(list), do: size(list) * 2

  def capture, do: &size/1
end

defmodule UsagesImplementationCallsClient do
  def by_implementation(data), do: UsagesImplementationCalls.List.size(data)
end
