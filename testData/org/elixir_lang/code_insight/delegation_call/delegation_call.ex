defmodule Target do
  def snoc(q, x), do: {q, x}
end

defmodule Delegator do
  defdelegate snoc(q, x), to: Target
end

defmodule Caller do
  def calls(a), do: {Delegator.sn<caret>oc(a, a), Delegator.snoc(a, a)}
end
