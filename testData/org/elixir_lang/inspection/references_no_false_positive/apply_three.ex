defmodule ApplyTarget do
  def snoc(a, b), do: [a | b]
end

defmodule ApplyCaller do
  def at_apply(a, b), do: apply(ApplyTarget, :snoc, [a, b])
end
