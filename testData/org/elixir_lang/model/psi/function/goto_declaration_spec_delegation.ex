defmodule Counter do
  def count(list), do: length(list)
end

defmodule GotoDeclarationSpecDelegation do
  @spec si<caret>ze(list) :: integer
  defdelegate size(list), to: Counter, as: :count
end
