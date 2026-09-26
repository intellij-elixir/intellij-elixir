defmodule DelegateTarget do
  def fresh(x), do: x
end

defmodule Delegator do
  defdelegate renamee(x), to: DelegateTarget, as: :fresh
end
