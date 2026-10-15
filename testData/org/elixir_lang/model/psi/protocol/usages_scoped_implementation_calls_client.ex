defmodule UsagesScopedImplementationCallsClient do
  def by_implementation(data), do: UsagesScopedImplementationCalls.List.size(data)
end
