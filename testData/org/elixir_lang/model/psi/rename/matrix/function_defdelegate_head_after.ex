defmodule DelegateTarget do
  def renamee(x), do: x
end

defmodule Delegator do
  defdelegate fresh(x), to: DelegateTarget, as: :renamee
end
