defprotocol UsagesScopedImplementationCalls do
  def si<caret>ze(data)
end

defimpl UsagesScopedImplementationCalls, for: List do
  def size(list), do: length(list)
end
