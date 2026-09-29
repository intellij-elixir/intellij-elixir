defmodule NormalizedAtomTarget do
  def snoć(a, b), do: [a | b]
end

defmodule NormalizedAtomUsage do
  @doc delegate_to: {NormalizedAtomTarget, :sno<caret>ć, 2}
  def caller, do: 1
end
